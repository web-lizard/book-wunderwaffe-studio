from __future__ import annotations

import json
import sys
import tempfile
import threading
import time
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from bookender.database import BookenderDatabase
from bookender.repository import ProjectRepository
from bookender.tts import TtsCancelledError, TtsService


ROOT = Path(__file__).resolve().parents[1]


class TtsServiceTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.user_data = Path(self.temp.name) / "user_data"
        self.patches = [
            patch("bookender.repository.USER_DATA", self.user_data),
            patch("bookender.tts.USER_DATA", self.user_data),
        ]
        for item in self.patches:
            item.start()
        self.repository = ProjectRepository(
            BookenderDatabase(
                self.user_data / "bookender.db",
                ROOT / "bookender" / "migrations",
            )
        )
        self.repository.initialize()
        self.project = self.repository.create_project(
            title="TTS тест",
            create_first_chapter=True,
            voice="ru-RU-SvetlanaNeural",
        )
        self.chapter = self.repository.update_chapter(
            self.project["uuid"],
            self.project["chapters"][0]["id"],
            content="Нейтральный текст для теста озвучивания.",
        )
        self.service = TtsService(self.repository)

    def tearDown(self) -> None:
        for item in reversed(self.patches):
            item.stop()
        self.temp.cleanup()

    @staticmethod
    def fake_synthesize(
        text: str,
        output: Path,
        settings: dict[str, str],
        progress_callback=None,
        **_: object,
    ) -> None:
        if progress_callback:
            progress_callback(1, 1, 1.0)
        output.write_bytes(b"ID3" + text.encode("utf-8"))

    def wait_for_job(self, job_uuid: str) -> dict:
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            job = self.service.get_job(job_uuid)
            if job["status"] not in {"queued", "running"}:
                return job
            time.sleep(0.02)
        self.fail("TTS job did not finish")

    def test_preview_is_temporary_and_not_registered_as_chapter_audio(self) -> None:
        with (
            patch.object(self.service, "require_available"),
            patch.object(self.service, "_synthesize", self.fake_synthesize),
            patch.object(self.service, "_audio_duration", return_value=1.25),
        ):
            preview = self.service.preview(
                self.project["uuid"],
                "Проба",
                {
                    "voice": "ru-RU-DmitryNeural",
                    "rate": "+15%",
                    "pitch": "-10Hz",
                    "volume": "+0%",
                    "provider": "edge-tts",
                },
            )
        self.assertTrue(preview["temporary"])
        self.assertTrue((self.user_data / preview["file_path"]).is_file())
        restored = self.repository.get_project(
            self.project["uuid"], include_details=True
        )
        self.assertEqual(restored["audio_assets"], [])

    def test_long_text_is_split_on_readable_boundaries(self) -> None:
        text = ("Первое предложение. Второе предложение! " * 180).strip()
        chunks = self.service._split_text_chunks(text, limit=240)
        self.assertGreater(len(chunks), 1)
        self.assertTrue(all(0 < len(chunk) <= 240 for chunk in chunks))
        self.assertEqual(" ".join(chunks).split(), text.split())

    def test_synthesis_reports_monotonic_chunk_progress(self) -> None:
        text = ("Нейтральное длинное предложение для индикатора. " * 130).strip()
        updates: list[tuple[int, int, float]] = []

        def fake_chunk(
            chunk: str,
            output: Path,
            settings: dict[str, str],
            progress_callback=None,
        ) -> None:
            if progress_callback:
                progress_callback(0.35)
                progress_callback(1.0)
            output.write_bytes(b"ID3" + chunk.encode("utf-8"))

        def fake_concat(parts: list[Path], output: Path, **_: object) -> None:
            output.write_bytes(b"".join(path.read_bytes() for path in parts))

        output = self.user_data / "progress.mp3"
        with (
            patch.object(TtsService, "_synthesize_chunk", side_effect=fake_chunk),
            patch.object(TtsService, "_concat_mp3_files", side_effect=fake_concat),
            patch("bookender.tts.shutil.which", return_value="ffmpeg"),
        ):
            self.service._synthesize(
                text,
                output,
                {
                    "voice": "ru-RU-DmitryNeural",
                    "rate": "+0%",
                    "pitch": "+0Hz",
                    "volume": "+0%",
                    "provider": "edge-tts",
                },
                progress_callback=lambda done, total, fraction: updates.append(
                    (done, total, fraction)
                ),
            )
        fractions = [item[2] for item in updates]
        self.assertGreater(len(updates), 4)
        self.assertEqual(fractions, sorted(fractions))
        self.assertEqual(updates[-1][0], updates[-1][1])
        self.assertEqual(updates[-1][2], 1.0)
        self.assertTrue(output.is_file())

    def test_synthesis_offsets_exact_word_timings_across_chunks(self) -> None:
        output = self.user_data / "timed.mp3"
        chunk_timings = [
            [{"text": "Первое", "start": 0.1, "end": 0.5}],
            [{"text": "Второе", "start": 0.2, "end": 0.7}],
        ]
        chunk_index = 0

        def fake_chunk(*args, **kwargs):
            nonlocal chunk_index
            args[1].write_bytes(f"part-{chunk_index}".encode())
            result = chunk_timings[chunk_index]
            chunk_index += 1
            return result

        def fake_concat(parts: list[Path], destination: Path, **_: object) -> None:
            destination.write_bytes(b"".join(path.read_bytes() for path in parts))

        with (
            patch.object(TtsService, "_split_text_chunks", return_value=["Первое", "Второе"]),
            patch.object(TtsService, "_synthesize_chunk", side_effect=fake_chunk),
            patch.object(TtsService, "_audio_duration", side_effect=[1.5, 2.0]),
            patch.object(TtsService, "_concat_mp3_files", side_effect=fake_concat),
            patch("bookender.tts.shutil.which", return_value="ffmpeg"),
        ):
            timings = self.service._synthesize(
                "Первое Второе",
                output,
                {
                    "voice": "ru-RU-DmitryNeural",
                    "rate": "+0%",
                    "pitch": "+0Hz",
                    "volume": "+0%",
                    "provider": "edge-tts",
                },
            )

        self.assertEqual(timings[0]["start"], 0.1)
        self.assertEqual(timings[1]["start"], 1.7)
        self.assertEqual(timings[1]["end"], 2.2)

    def test_chunk_requests_and_converts_word_boundaries(self) -> None:
        requested: dict[str, object] = {}

        class FakeCommunicate:
            def __init__(self, text, voice, **kwargs):
                requested.update({"text": text, "voice": voice, **kwargs})

            async def stream(self):
                yield {"type": "audio", "data": b"ID3"}
                yield {
                    "type": "WordBoundary",
                    "text": "Слово",
                    "offset": 1_250_000,
                    "duration": 3_750_000,
                }

        output = self.user_data / "boundary.mp3"
        edge_tts = types.SimpleNamespace(Communicate=FakeCommunicate)
        with patch.dict(sys.modules, {"edge_tts": edge_tts}):
            timings = self.service._synthesize_chunk(
                "Слово",
                output,
                {
                    "voice": "ru-RU-DmitryNeural",
                    "rate": "+0%",
                    "pitch": "+0Hz",
                    "volume": "+0%",
                    "provider": "edge-tts",
                },
            )

        self.assertEqual(requested["boundary"], "WordBoundary")
        self.assertEqual(requested["connect_timeout"], 12)
        self.assertEqual(requested["receive_timeout"], 30)
        self.assertEqual(
            timings,
            [{"text": "Слово", "start": 0.125, "end": 0.5}],
        )

    def test_chunk_watchdog_interrupts_a_stream_that_never_yields(self) -> None:
        class HungCommunicate:
            def __init__(self, *args, **kwargs):
                pass

            async def stream(self):
                await __import__("asyncio").Event().wait()
                if False:
                    yield {}

        output = self.user_data / "hung.mp3"
        edge_tts = types.SimpleNamespace(Communicate=HungCommunicate)
        started = time.monotonic()
        with (
            patch.dict(sys.modules, {"edge_tts": edge_tts}),
            patch("bookender.tts.TTS_STREAM_POLL_SECONDS", 0.005),
            patch("bookender.tts.TTS_STREAM_STALL_TIMEOUT_SECONDS", 0.03),
            patch("bookender.tts.TTS_RETRY_ATTEMPTS", 1),
            self.assertRaises(TimeoutError),
        ):
            self.service._synthesize_chunk(
                "Зависший фрагмент",
                output,
                {
                    "voice": "ru-RU-DmitryNeural",
                    "rate": "+0%",
                    "pitch": "+0Hz",
                    "volume": "+0%",
                    "provider": "edge-tts",
                },
            )

        self.assertLess(time.monotonic() - started, 1.0)
        self.assertFalse(output.exists())

    def test_long_synthesis_resumes_from_completed_chunk_checkpoints(self) -> None:
        settings = {
            "voice": "ru-RU-DmitryNeural",
            "rate": "+0%",
            "pitch": "+0Hz",
            "volume": "+0%",
            "provider": "edge-tts",
        }
        calls: list[str] = []
        fail_second = True

        def fake_chunk(text, output, settings, **kwargs):
            nonlocal fail_second
            calls.append(text)
            if text == "Второй" and fail_second:
                raise TimeoutError("provider stalled")
            output.write_bytes(b"ID3-" + text.encode("utf-8"))
            return [{"text": text, "start": 0.1, "end": 0.8}]

        def fake_concat(parts, output, **kwargs):
            output.write_bytes(b"".join(part.read_bytes() for part in parts))

        events: list[str] = []
        with (
            patch.object(TtsService, "_split_text_chunks", return_value=["Первый", "Второй"]),
            patch.object(TtsService, "_synthesize_chunk", side_effect=fake_chunk),
            patch.object(TtsService, "_audio_duration", return_value=1.0),
            patch.object(TtsService, "_concat_mp3_files", side_effect=fake_concat),
            patch("bookender.tts.shutil.which", return_value="ffmpeg"),
        ):
            with self.assertRaises(TimeoutError):
                self.service._synthesize("текст", self.user_data / "first.mp3", settings)

            fail_second = False
            timings = self.service._synthesize(
                "текст",
                self.user_data / "retry.mp3",
                settings,
                event_callback=lambda name, message, details: events.append(name),
            )

        self.assertEqual(calls, ["Первый", "Второй", "Второй"])
        self.assertIn("chunk_resumed", events)
        self.assertEqual(timings[0]["text"], "Первый")
        self.assertEqual(timings[1]["start"], 1.1)
        self.assertTrue((self.user_data / "retry.mp3").is_file())

    def test_generated_audio_persists_exact_word_timings(self) -> None:
        exact = [{"text": "Нейтральный", "start": 0.12, "end": 0.66}]

        def fake_timed_synthesis(*args, **kwargs):
            output = args[1]
            output.write_bytes(b"ID3-timed")
            return exact

        with (
            patch.object(self.service, "require_available"),
            patch.object(self.service, "_synthesize", side_effect=fake_timed_synthesis),
            patch.object(self.service, "_audio_duration", return_value=2.5),
        ):
            job = self.wait_for_job(
                self.service.queue_chapter(
                    self.project["uuid"], self.chapter["id"]
                )["uuid"]
            )

        self.assertEqual(job["status"], "done")
        restored = self.repository.get_project(
            self.project["uuid"], include_details=True
        )
        metadata = json.loads(restored["audio_assets"][0]["metadata_json"])
        self.assertEqual(metadata["word_timings"], exact)

    def test_running_job_can_be_cancelled_and_keeps_diagnostics(self) -> None:
        started = threading.Event()

        def cancellable_synthesis(*args, cancel_check=None, **kwargs):
            started.set()
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                if cancel_check and cancel_check():
                    raise TtsCancelledError("cancelled in test")
                time.sleep(0.01)
            self.fail("Cancellation was not delivered to synthesis")

        with (
            patch.object(self.service, "require_available"),
            patch.object(self.service, "_synthesize", side_effect=cancellable_synthesis),
        ):
            queued = self.service.queue_chapter(
                self.project["uuid"], self.chapter["id"]
            )
            self.assertTrue(started.wait(1))
            stopping = self.service.cancel_job(queued["uuid"])
            self.assertTrue(stopping["cancel_requested"])
            finished = self.wait_for_job(queued["uuid"])

        self.assertEqual(finished["status"], "cancelled")
        self.assertEqual(finished["stage"], "Отменено")
        events = [item["event"] for item in finished["diagnostics"]]
        self.assertIn("cancel_requested", events)
        self.assertIn("job_cancelled", events)

    def test_book_queue_runs_two_chapters_in_parallel(self) -> None:
        self.repository.create_chapter(
            self.project["uuid"], title="Вторая", content="Вторая глава."
        )
        active = 0
        maximum = 0
        lock = threading.Lock()

        def concurrent_synthesis(text, output, settings, progress_callback=None, **kwargs):
            nonlocal active, maximum
            with lock:
                active += 1
                maximum = max(maximum, active)
            try:
                time.sleep(0.12)
                output.write_bytes(b"ID3" + text.encode("utf-8"))
                if progress_callback:
                    progress_callback(1, 1, 1.0)
            finally:
                with lock:
                    active -= 1

        with (
            patch.object(self.service, "require_available"),
            patch.object(self.service, "_synthesize", side_effect=concurrent_synthesis),
            patch.object(self.service, "_audio_duration", return_value=1.0),
        ):
            jobs = self.service.queue_book(self.project["uuid"])
            finished = [self.wait_for_job(job["uuid"]) for job in jobs]

        self.assertEqual([job["status"] for job in finished], ["done", "done"])
        self.assertGreaterEqual(maximum, 2)

    def test_chapter_versions_remain_isolated_and_become_stale(self) -> None:
        with (
            patch.object(self.service, "require_available"),
            patch.object(self.service, "_synthesize", self.fake_synthesize),
            patch.object(self.service, "_audio_duration", return_value=2.5),
        ):
            first = self.wait_for_job(
                self.service.queue_chapter(
                    self.project["uuid"], self.chapter["id"]
                )["uuid"]
            )
            second = self.wait_for_job(
                self.service.queue_chapter(
                    self.project["uuid"], self.chapter["id"]
                )["uuid"]
            )
        self.assertEqual(first["status"], "done")
        self.assertEqual(second["status"], "done")
        restored = self.repository.get_project(
            self.project["uuid"], include_details=True
        )
        assets = [
            item
            for item in restored["audio_assets"]
            if item["chapter_id"] == self.chapter["id"]
        ]
        self.assertEqual([item["version_number"] for item in assets], [2, 1])
        self.assertEqual(sum(bool(item["is_active"]) for item in assets), 1)
        self.assertNotEqual(assets[0]["file_path"], assets[1]["file_path"])
        self.repository.update_chapter(
            self.project["uuid"], self.chapter["id"], content="Текст изменён."
        )
        changed = self.repository.get_project(
            self.project["uuid"], include_details=True
        )
        self.assertTrue(all(item["is_stale"] for item in changed["audio_assets"]))
        with self.assertRaisesRegex(ValueError, "нет готовых озвучек"):
            self.service.build_video_master(self.project["uuid"])

    def test_video_master_uses_active_chapter_audio_in_book_order(self) -> None:
        second = self.repository.create_chapter(
            self.project["uuid"],
            title="Вторая глава",
            content="Вторая нейтральная глава.",
        )
        with (
            patch.object(self.service, "require_available"),
            patch.object(self.service, "_synthesize", self.fake_synthesize),
            patch.object(self.service, "_audio_duration", return_value=2.5),
        ):
            first_job = self.wait_for_job(
                self.service.queue_chapter(
                    self.project["uuid"], self.chapter["id"]
                )["uuid"]
            )
            second_job = self.wait_for_job(
                self.service.queue_chapter(
                    self.project["uuid"], second["id"]
                )["uuid"]
            )
        self.assertEqual(first_job["status"], "done")
        self.assertEqual(second_job["status"], "done")

        with self.repository.database.transaction() as connection:
            connection.execute(
                "UPDATE audio_assets SET metadata_json=? WHERE chapter_id=?",
                (json.dumps({"word_timings": [{"text": "Первое", "start": 0.2, "end": 0.6}]}), self.chapter["id"]),
            )
            connection.execute(
                "UPDATE audio_assets SET metadata_json=? WHERE chapter_id=?",
                (json.dumps({"word_timings": [{"text": "Второе", "start": 0.3, "end": 0.8}]}), second["id"]),
            )

        def fake_concat(parts: list[Path], output: Path, **_: object) -> None:
            output.write_bytes(b"".join(path.read_bytes() for path in parts))

        with patch.object(self.service, "_concat_mp3_files", fake_concat):
            master = self.service.build_video_master(self.project["uuid"])
        self.assertEqual(len(master["source_asset_ids"]), 2)
        self.assertEqual(
            [item["chapter_id"] for item in master["chapters"]],
            [self.chapter["id"], second["id"]],
        )
        self.assertEqual(master["chapters"][0]["start_seconds"], 0)
        self.assertEqual(master["chapters"][1]["start_seconds"], 2.5)
        self.assertEqual(master["chapters"][0]["word_timings"][0]["start"], 0.2)
        self.assertEqual(master["chapters"][1]["word_timings"][0]["start"], 2.8)
        self.assertEqual(master["duration"], 5.0)
        self.assertTrue((self.user_data / master["file_path"]).is_file())


if __name__ == "__main__":
    unittest.main()
