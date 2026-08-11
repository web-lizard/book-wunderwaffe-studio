"""Project-aware Edge TTS voices, previews, and versioned narration jobs."""

from __future__ import annotations

import asyncio
import importlib.util
import json
import queue as queue_module
import re
import shutil
import subprocess
import threading
import time
import uuid as uuidlib
from pathlib import Path
from typing import Any, Callable

from .logging import log_event
from .paths import USER_DATA
from .repository import ProjectNotFoundError, ProjectRepository, utc_now


DEFAULT_VOICE = "ru-RU-SvetlanaNeural"
TTS_CHUNK_LIMIT = 2_400
TTS_CONNECT_TIMEOUT_SECONDS = 12
TTS_RECEIVE_TIMEOUT_SECONDS = 30
TTS_RETRY_ATTEMPTS = 3
TTS_MAX_PARALLEL_CHAPTERS = 2
TTS_DIAGNOSTIC_LIMIT = 120
RATE_PATTERN = re.compile(r"^[+-](?:100|[0-9]{1,2})%$")
PITCH_PATTERN = re.compile(r"^[+-](?:[0-9]{1,3})Hz$")
VOLUME_PATTERN = re.compile(r"^[+-](?:100|[0-9]{1,2})%$")
RATE_VALUES = {"-25%", "-10%", "+0%", "+15%", "+30%"}
PITCH_VALUES = {"-40Hz", "-25Hz", "-10Hz", "+0Hz", "+10Hz", "+25Hz", "+40Hz"}
TTS_UNAVAILABLE_MESSAGE = (
    "Модуль озвучки не запущен. Проверьте установку компонентов TTS."
)
TTS_FAILED_MESSAGE = (
    "Не удалось создать озвучку. Проверьте подключение к интернету и повторите."
)
FALLBACK_VOICES = [
    {
        "id": "ru-RU-DmitryNeural",
        "name": "Дмитрий",
        "friendly_name": "Microsoft Dmitry Online (Natural) - Russian (Russia)",
        "language": "ru",
        "locale": "ru-RU",
        "region": "RU",
        "gender": "Male",
        "suggested": True,
    },
    {
        "id": "ru-RU-SvetlanaNeural",
        "name": "Светлана",
        "friendly_name": "Microsoft Svetlana Online (Natural) - Russian (Russia)",
        "language": "ru",
        "locale": "ru-RU",
        "region": "RU",
        "gender": "Female",
        "suggested": True,
    },
]


class TtsUnavailableError(RuntimeError):
    """Raised when the configured TTS runtime cannot be imported."""


class TtsCancelledError(RuntimeError):
    """Raised cooperatively when a narration job is cancelled."""


class TtsService:
    def __init__(self, repository: ProjectRepository) -> None:
        self.repository = repository
        self._threads: dict[str, threading.Thread] = {}
        self._cancel_events: dict[str, threading.Event] = {}
        self._lock = threading.Lock()
        self._voice_cache: tuple[float, list[dict[str, Any]]] | None = None
        self._recover_interrupted_jobs()

    def _recover_interrupted_jobs(self) -> None:
        try:
            with self.repository.database.transaction() as connection:
                result = connection.execute(
                    """
                    UPDATE tts_jobs
                    SET status='failed', progress=0, finished_at=?,
                        error='TTS worker stopped before completion',
                        user_error='Предыдущая задача озвучки была прервана. Запустите её повторно.',
                        stage='Прервано при перезапуске', last_activity_at=?
                    WHERE status IN ('queued', 'running')
                    """,
                    (utc_now(), utc_now()),
                )
            if result.rowcount:
                log_event("tts_interrupted_jobs_recovered", count=result.rowcount)
        except Exception as exc:
            log_event("tts_job_recovery_failed", error=repr(exc))

    def dependency_status(self) -> dict[str, Any]:
        spec = importlib.util.find_spec("edge_tts")
        if spec is None:
            return {
                "available": False,
                "provider": "edge-tts",
                "message": TTS_UNAVAILABLE_MESSAGE,
            }
        try:
            import edge_tts

            version = getattr(edge_tts, "__version__", "")
        except Exception as exc:
            log_event("tts_startup_check_failed", error=repr(exc))
            return {
                "available": False,
                "provider": "edge-tts",
                "message": TTS_UNAVAILABLE_MESSAGE,
            }
        return {
            "available": True,
            "provider": "edge-tts",
            "version": version,
            "message": "Компонент озвучки готов.",
        }

    def require_available(self) -> None:
        status = self.dependency_status()
        if not status["available"]:
            log_event("tts_runtime_unavailable", status=status)
            raise TtsUnavailableError(TTS_UNAVAILABLE_MESSAGE)

    def normalize_settings(self, settings: dict[str, Any]) -> dict[str, str]:
        return self._normalized_settings(settings)

    def list_voices(self, *, refresh: bool = False) -> list[dict[str, Any]]:
        self.require_available()
        now = time.monotonic()
        if not refresh and self._voice_cache and now - self._voice_cache[0] < 21_600:
            return self._voice_cache[1]
        try:
            import edge_tts

            raw_voices = asyncio.run(edge_tts.list_voices())
            voices = []
            for raw in raw_voices:
                short_name = str(raw.get("ShortName") or "").strip()
                locale = str(raw.get("Locale") or "").strip()
                if not short_name:
                    continue
                language, _, region = locale.partition("-")
                technical_name = short_name.split("-", 2)[-1]
                for suffix in ("MultilingualNeural", "Neural"):
                    if technical_name.endswith(suffix):
                        technical_name = technical_name[: -len(suffix)]
                        break
                localized_names = {
                    "ru-RU-DmitryNeural": "Дмитрий",
                    "ru-RU-SvetlanaNeural": "Светлана",
                }
                display_name = localized_names.get(short_name, technical_name)
                voices.append(
                    {
                        "id": short_name,
                        "name": str(display_name),
                        "friendly_name": str(raw.get("FriendlyName") or ""),
                        "language": language,
                        "locale": locale,
                        "region": region,
                        "gender": str(raw.get("Gender") or ""),
                        "suggested": locale.casefold().startswith("ru-"),
                    }
                )
            voices.sort(
                key=lambda voice: (
                    not voice["suggested"],
                    voice["language"].casefold(),
                    voice["name"].casefold(),
                )
            )
            self._voice_cache = (now, voices)
            log_event("tts_voices_loaded", count=len(voices))
            return voices
        except Exception as exc:
            log_event("tts_voice_list_failed", error=repr(exc))
            voices = [dict(voice) for voice in FALLBACK_VOICES]
            self._voice_cache = (now, voices)
            return voices

    def preview(
        self, project_uuid: str, text: str, settings: dict[str, Any]
    ) -> dict[str, Any]:
        self.require_available()
        project = self.repository.get_project(project_uuid, include_details=True)
        clean_text = " ".join(str(text or "").split())[:500]
        if not clean_text:
            clean_text = (
                "Это короткая проба голоса Book Wunderwaffe Studio. "
                "Вы можете изменить скорость и высоту тона."
            )
        normalized = self._normalized_settings(settings)
        relative_output = (
            Path(project["project_folder"])
            / "temp"
            / f"voice-preview-{uuidlib.uuid4().hex}.mp3"
        ).as_posix()
        output = self._absolute_project_path(project, relative_output)
        output.parent.mkdir(parents=True, exist_ok=True)
        try:
            self._synthesize(clean_text, output, normalized)
        except Exception as exc:
            output.unlink(missing_ok=True)
            log_event(
                "tts_preview_failed",
                project_uuid=project_uuid,
                voice=normalized["voice"],
                error=repr(exc),
            )
            raise RuntimeError(TTS_FAILED_MESSAGE) from exc
        result = {
            "file_path": relative_output,
            "voice": normalized["voice"],
            "rate": normalized["rate"],
            "pitch": normalized["pitch"],
            "volume": normalized["volume"],
            "duration": self._audio_duration(output),
            "file_size": output.stat().st_size,
            "temporary": True,
        }
        log_event(
            "tts_preview_ready",
            project_uuid=project_uuid,
            voice=normalized["voice"],
            file_size=result["file_size"],
        )
        return result

    def queue_chapter(
        self, project_uuid: str, chapter_id: int, *, start: bool = True
    ) -> dict[str, Any]:
        self.require_available()
        project = self.repository.get_project(project_uuid, include_details=True)
        chapter = next(
            (item for item in project["chapters"] if item["id"] == chapter_id),
            None,
        )
        if chapter is None:
            raise ProjectNotFoundError(f"{project_uuid}/chapter/{chapter_id}")
        if not chapter["content"].strip():
            raise ValueError("Пустую главу нельзя озвучить.")
        settings = self._normalized_settings(project.get("tts_settings") or {})
        job_uuid = str(uuidlib.uuid4())
        with self.repository.database.connect() as connection:
            version_number = int(
                connection.execute(
                    """
                    SELECT COALESCE(MAX(version_number), 0) + 1
                    FROM audio_assets
                    WHERE project_id=? AND chapter_id=?
                    """,
                    (project["id"], chapter_id),
                ).fetchone()[0]
            )
        relative_output = (
            Path(project["project_folder"])
            / "audio"
            / (
                f"chapter-{chapter_id}-v{version_number:03d}-"
                f"{chapter['content_hash'][:8]}-{job_uuid[:8]}.mp3"
            )
        ).as_posix()
        now = utc_now()
        diagnostics = json.dumps(
            [
                {
                    "timestamp": now,
                    "level": "info",
                    "event": "queued",
                    "message": "Задача добавлена в очередь",
                    "details": {
                        "chapter_id": chapter_id,
                        "chunks": len(self._split_text_chunks(chapter["content"])),
                    },
                }
            ],
            ensure_ascii=False,
            separators=(",", ":"),
        )
        with self.repository.database.transaction() as connection:
            connection.execute(
                """
                INSERT INTO tts_jobs(
                    uuid, project_id, chapter_id, status, provider, voice,
                    source_text_hash, output_path, created_at, job_kind, progress,
                    progress_done, progress_total, stage, diagnostics_json,
                    cancel_requested, last_activity_at
                ) VALUES (?, ?, ?, 'queued', ?, ?, ?, ?, ?, 'chapter', 0, 0, ?,
                    'В очереди', ?, 0, ?)
                """,
                (
                    job_uuid,
                    project["id"],
                    chapter_id,
                    settings["provider"],
                    settings["voice"],
                    chapter["content_hash"],
                    relative_output,
                    now,
                    len(self._split_text_chunks(chapter["content"])),
                    diagnostics,
                    now,
                ),
            )
        with self._lock:
            self._cancel_events[job_uuid] = threading.Event()
        if start:
            thread = threading.Thread(
                target=self._run,
                args=(
                    job_uuid,
                    project_uuid,
                    chapter,
                    settings,
                    relative_output,
                    version_number,
                ),
                name=f"bookender-tts-{job_uuid[:8]}",
                daemon=True,
            )
            with self._lock:
                self._threads[job_uuid] = thread
            thread.start()
        log_event(
            "tts_job_queued",
            job_uuid=job_uuid,
            project_uuid=project_uuid,
            chapter_id=chapter_id,
            source_text_hash=chapter["content_hash"],
            version_number=version_number,
        )
        return self.get_job(job_uuid)

    def queue_book(self, project_uuid: str) -> list[dict[str, Any]]:
        self.require_available()
        project = self.repository.get_project(project_uuid, include_details=True)
        jobs = [
            self.queue_chapter(project_uuid, chapter["id"], start=False)
            for chapter in project["chapters"]
            if chapter["content"].strip()
        ]
        if not jobs:
            return []
        batch_id = f"batch-{uuidlib.uuid4()}"
        pending: queue_module.Queue[str] = queue_module.Queue()
        for job in jobs:
            pending.put(job["uuid"])
        worker_count = min(TTS_MAX_PARALLEL_CHAPTERS, len(jobs))
        workers = []
        for index in range(worker_count):
            worker_key = f"{batch_id}-worker-{index + 1}"
            workers.append(
                (
                    worker_key,
                    threading.Thread(
                        target=self._run_batch_worker,
                        args=(worker_key, pending),
                        name=f"bookender-tts-{batch_id[6:14]}-{index + 1}",
                        daemon=True,
                    ),
                )
            )
        with self._lock:
            for worker_key, thread in workers:
                self._threads[worker_key] = thread
        for _, thread in workers:
            thread.start()
        log_event(
            "tts_book_queued",
            batch_id=batch_id,
            project_uuid=project_uuid,
            chapter_count=len(jobs),
            worker_count=worker_count,
        )
        return jobs

    def get_job(self, job_uuid: str) -> dict[str, Any]:
        with self.repository.database.connect() as connection:
            row = connection.execute(
                """
                SELECT j.*, p.uuid AS project_uuid
                FROM tts_jobs j JOIN projects p ON p.id=j.project_id
                WHERE j.uuid=?
                """,
                (job_uuid,),
            ).fetchone()
            if row is None:
                raise ProjectNotFoundError(f"tts-job/{job_uuid}")
            return self._job_payload(dict(row))

    def list_jobs(self, project_uuid: str) -> list[dict[str, Any]]:
        with self.repository.database.connect() as connection:
            return [
                self._job_payload(dict(row))
                for row in connection.execute(
                    """
                    SELECT j.* FROM tts_jobs j
                    JOIN projects p ON p.id=j.project_id
                    WHERE p.uuid=? ORDER BY j.created_at DESC
                    """,
                    (project_uuid,),
                ).fetchall()
            ]

    def cancel_job(self, job_uuid: str) -> dict[str, Any]:
        job = self.get_job(job_uuid)
        if job["status"] not in {"queued", "running"}:
            return job
        now = utc_now()
        event = {
            "timestamp": now,
            "level": "warning",
            "event": "cancel_requested",
            "message": "Пользователь запросил остановку",
            "details": {},
        }
        diagnostics = list(job.get("diagnostics") or [])
        diagnostics.append(event)
        diagnostics = diagnostics[-TTS_DIAGNOSTIC_LIMIT:]
        with self.repository.database.transaction() as connection:
            connection.execute(
                """
                UPDATE tts_jobs
                SET cancel_requested=1,
                    status=CASE WHEN status='queued' THEN 'cancelled' ELSE status END,
                    stage=CASE WHEN status='queued' THEN 'Отменено' ELSE 'Останавливаю…' END,
                    diagnostics_json=?, last_activity_at=?,
                    finished_at=CASE WHEN status='queued' THEN ? ELSE finished_at END,
                    user_error=CASE WHEN status='queued' THEN 'Озвучивание отменено.' ELSE user_error END
                WHERE uuid=? AND status IN ('queued', 'running')
                """,
                (
                    json.dumps(diagnostics, ensure_ascii=False, separators=(",", ":")),
                    now,
                    now,
                    job_uuid,
                ),
            )
        with self._lock:
            cancel_event = self._cancel_events.get(job_uuid)
        if cancel_event is not None:
            cancel_event.set()
        log_event(
            "tts_job_cancel_requested",
            job_uuid=job_uuid,
            project_uuid=job["project_uuid"],
            chapter_id=job["chapter_id"],
        )
        return self.get_job(job_uuid)

    def cancel_project_jobs(self, project_uuid: str) -> list[dict[str, Any]]:
        active = [
            job
            for job in self.list_jobs(project_uuid)
            if job["status"] in {"queued", "running"}
        ]
        return [self.cancel_job(job["uuid"]) for job in active]

    @staticmethod
    def _job_payload(job: dict[str, Any]) -> dict[str, Any]:
        try:
            diagnostics = json.loads(str(job.get("diagnostics_json") or "[]"))
        except (TypeError, json.JSONDecodeError):
            diagnostics = []
        job["diagnostics"] = diagnostics if isinstance(diagnostics, list) else []
        return job

    def _append_diagnostic(
        self,
        job_uuid: str,
        event: str,
        message: str,
        *,
        level: str = "info",
        stage: str | None = None,
        **details: Any,
    ) -> None:
        now = utc_now()
        with self.repository.database.transaction() as connection:
            row = connection.execute(
                "SELECT diagnostics_json FROM tts_jobs WHERE uuid=?", (job_uuid,)
            ).fetchone()
            if row is None:
                return
            try:
                diagnostics = json.loads(str(row["diagnostics_json"] or "[]"))
            except (TypeError, json.JSONDecodeError):
                diagnostics = []
            if not isinstance(diagnostics, list):
                diagnostics = []
            diagnostics.append(
                {
                    "timestamp": now,
                    "level": level,
                    "event": event,
                    "message": message,
                    "details": details,
                }
            )
            diagnostics = diagnostics[-TTS_DIAGNOSTIC_LIMIT:]
            connection.execute(
                """
                UPDATE tts_jobs
                SET diagnostics_json=?, stage=COALESCE(?, stage), last_activity_at=?
                WHERE uuid=?
                """,
                (
                    json.dumps(diagnostics, ensure_ascii=False, separators=(",", ":")),
                    stage,
                    now,
                    job_uuid,
                ),
            )
        log_event(
            f"tts_{event}",
            job_uuid=job_uuid,
            message=message,
            **details,
        )

    def _cancel_requested(self, job_uuid: str) -> bool:
        with self._lock:
            cancel_event = self._cancel_events.get(job_uuid)
        if cancel_event is not None:
            return cancel_event.is_set()
        with self.repository.database.connect() as connection:
            row = connection.execute(
                "SELECT cancel_requested, status FROM tts_jobs WHERE uuid=?",
                (job_uuid,),
            ).fetchone()
        return bool(row and (row["cancel_requested"] or row["status"] == "cancelled"))

    def build_video_master(self, project_uuid: str) -> dict[str, Any]:
        project = self.repository.get_project(project_uuid, include_details=True)
        audio_by_chapter: dict[int, dict[str, Any]] = {}
        for asset in project.get("audio_assets", []):
            chapter_id = int(asset.get("chapter_id") or 0)
            if not chapter_id:
                continue
            current = audio_by_chapter.get(chapter_id)
            asset_rank = (
                int(bool(asset.get("is_active"))),
                int(asset.get("version_number") or 0),
                int(asset["id"]),
            )
            current_rank = (
                int(bool(current and current.get("is_active"))),
                int((current or {}).get("version_number") or 0),
                int((current or {}).get("id") or 0),
            )
            if current is None or asset_rank > current_rank:
                audio_by_chapter[chapter_id] = asset

        selected: list[tuple[dict[str, Any], dict[str, Any], Path, float]] = []
        missing_chapter_ids: list[int] = []
        for chapter in project.get("chapters", []):
            asset = audio_by_chapter.get(int(chapter["id"]))
            if (
                not asset
                or not asset.get("file_path")
                or bool(asset.get("is_stale"))
                or (
                    asset.get("source_text_hash")
                    and asset.get("source_text_hash") != chapter.get("content_hash")
                )
            ):
                missing_chapter_ids.append(int(chapter["id"]))
                continue
            path = self._absolute_project_path(project, str(asset["file_path"]))
            if not path.is_file():
                missing_chapter_ids.append(int(chapter["id"]))
                continue
            duration = float(asset.get("duration") or self._audio_duration(path) or 0)
            if duration <= 0:
                raise RuntimeError(f"Cannot determine duration of {path.name}")
            selected.append((chapter, asset, path, duration))
        if not selected:
            raise ValueError("У книги пока нет готовых озвучек для передачи в видео.")

        source_fingerprint = "|".join(
            f"{asset['id']}:{path.stat().st_size}:{path.stat().st_mtime_ns}"
            for _, asset, path, _ in selected
        )
        digest = uuidlib.uuid5(uuidlib.NAMESPACE_URL, source_fingerprint).hex[:12]
        if len(selected) == 1:
            relative_output = str(selected[0][1]["file_path"])
            output = selected[0][2]
        else:
            relative_output = (
                Path(project["project_folder"])
                / "video"
                / f"book-master-{digest}.mp3"
            ).as_posix()
            output = self._absolute_project_path(project, relative_output)
            output.parent.mkdir(parents=True, exist_ok=True)
            if not output.is_file() or output.stat().st_size <= 0:
                self._concat_mp3_files([item[2] for item in selected], output)

        cursor = 0.0
        chapters = []
        for chapter, asset, _, chapter_duration in selected:
            start = cursor
            cursor += chapter_duration
            try:
                metadata = json.loads(str(asset.get("metadata_json") or "{}"))
            except (TypeError, json.JSONDecodeError):
                metadata = {}
            relative_word_timings = (
                metadata.get("word_timings", [])
                if isinstance(metadata, dict)
                else []
            )
            word_timings = []
            for timing in relative_word_timings:
                if not isinstance(timing, dict):
                    continue
                try:
                    word_start = start + float(timing["start"])
                    word_end = start + float(timing["end"])
                except (KeyError, TypeError, ValueError):
                    continue
                if word_end < word_start:
                    continue
                word_timings.append(
                    {
                        "text": str(timing.get("text") or ""),
                        "start": round(word_start, 4),
                        "end": round(word_end, 4),
                    }
                )
            chapters.append(
                {
                    "chapter_id": int(chapter["id"]),
                    "title": chapter["title"],
                    "text": chapter["content"],
                    "audio_asset_id": int(asset["id"]),
                    "start_seconds": round(start, 3),
                    "end_seconds": round(cursor, 3),
                    "duration_seconds": round(chapter_duration, 3),
                    "word_timings": word_timings,
                }
            )
        result = {
            "file_path": relative_output,
            "file_size": output.stat().st_size,
            "duration": round(cursor, 3),
            "chapters": chapters,
            "missing_chapter_ids": missing_chapter_ids,
            "source_asset_ids": [int(item[1]["id"]) for item in selected],
        }
        log_event(
            "book_video_master_ready",
            project_uuid=project_uuid,
            chapter_count=len(chapters),
            missing_chapter_count=len(missing_chapter_ids),
            output_path=relative_output,
            duration=result["duration"],
        )
        return result

    def _run(
        self,
        job_uuid: str,
        project_uuid: str,
        chapter: dict[str, Any],
        settings: dict[str, Any],
        relative_output: str,
        version_number: int,
    ) -> None:
        existing_job = self.get_job(job_uuid)
        if existing_job["status"] == "cancelled" or existing_job["cancel_requested"]:
            with self._lock:
                self._cancel_events.pop(job_uuid, None)
            return
        project_record = self.repository.get_project(
            project_uuid, include_details=False
        )
        output = self._absolute_project_path(project_record, relative_output)
        output.parent.mkdir(parents=True, exist_ok=True)
        with self.repository.database.transaction() as connection:
            started = connection.execute(
                """
                UPDATE tts_jobs
                SET status='running', progress=0.02, progress_done=0, started_at=?,
                    stage='Подготовка', last_activity_at=?
                WHERE uuid=? AND status='queued' AND cancel_requested=0
                """,
                (utc_now(), utc_now(), job_uuid),
            )
        if started.rowcount != 1:
            with self._lock:
                self._cancel_events.pop(job_uuid, None)
            return
        self._append_diagnostic(
            job_uuid,
            "job_started",
            "Фоновый обработчик начал озвучивание",
            stage="Подготовка",
            chunks=len(self._split_text_chunks(chapter["content"])),
        )
        try:
            last_saved_progress = 0.0
            chapter_chunk_count = len(self._split_text_chunks(chapter["content"]))

            def cancel_requested() -> bool:
                return self._cancel_requested(job_uuid)

            def save_event(
                event: str, message: str, details: dict[str, Any]
            ) -> None:
                level = "warning" if event in {"network_retry", "network_error"} else "info"
                self._append_diagnostic(
                    job_uuid,
                    event,
                    message,
                    level=level,
                    stage=message,
                    **details,
                )

            def save_progress(
                completed: int, total: int, fraction: float
            ) -> None:
                nonlocal last_saved_progress
                if cancel_requested():
                    raise TtsCancelledError("Narration cancelled")
                progress = min(0.94, max(0.02, 0.02 + fraction * 0.92))
                if progress < 0.94 and progress - last_saved_progress < 0.015:
                    return
                last_saved_progress = progress
                with self.repository.database.transaction() as connection:
                    connection.execute(
                        """
                        UPDATE tts_jobs
                        SET progress=?, progress_done=?, progress_total=?,
                            last_activity_at=?
                        WHERE uuid=? AND status='running'
                        """,
                        (progress, completed, max(1, total), utc_now(), job_uuid),
                    )

            word_timings = self._synthesize(
                chapter["content"],
                output,
                settings,
                progress_callback=save_progress,
                event_callback=save_event,
                cancel_check=cancel_requested,
            ) or []
            if cancel_requested():
                raise TtsCancelledError("Narration cancelled")
            save_progress(
                chapter_chunk_count,
                chapter_chunk_count,
                1.0,
            )
            duration = self._audio_duration(output)
            now = utc_now()
            self._append_diagnostic(
                job_uuid,
                "job_finished",
                "Озвучка готова",
                stage="Сохраняю готовую версию",
                file_size=output.stat().st_size,
                duration=duration,
            )
            with self.repository.database.transaction() as connection:
                project = connection.execute(
                    "SELECT id FROM projects WHERE uuid=?", (project_uuid,)
                ).fetchone()
                connection.execute(
                    """
                    UPDATE audio_assets SET is_active=0
                    WHERE project_id=? AND chapter_id=?
                    """,
                    (project["id"], chapter["id"]),
                )
                connection.execute(
                    """
                    INSERT INTO audio_assets(
                        project_id, chapter_id, file_path, generation_status,
                        voice, source_text_hash, created_at, updated_at,
                        title, rate, pitch, volume, version_number, is_active,
                        duration, file_size, metadata_json
                    ) VALUES (?, ?, ?, 'ready', ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
                    """,
                    (
                        project["id"],
                        chapter["id"],
                        relative_output,
                        settings["voice"],
                        chapter["content_hash"],
                        now,
                        now,
                        f"{chapter['title']} · версия {version_number}",
                        settings["rate"],
                        settings["pitch"],
                        settings["volume"],
                        version_number,
                        duration,
                        output.stat().st_size,
                        json.dumps(
                            {
                                "provider": settings["provider"],
                                "word_timings": word_timings,
                            },
                            ensure_ascii=False,
                            separators=(",", ":"),
                        ),
                    ),
                )
                connection.execute(
                    """
                    UPDATE tts_jobs
                    SET status='done', progress=1,
                        progress_done=progress_total, finished_at=?,
                        error='', user_error='', stage='Готово',
                        last_activity_at=?
                    WHERE uuid=?
                    """,
                    (now, now, job_uuid),
                )
            log_event(
                "tts_job_finished",
                job_uuid=job_uuid,
                project_uuid=project_uuid,
                chapter_id=chapter["id"],
                output_path=relative_output,
                file_size=output.stat().st_size,
                duration=duration,
            )
        except TtsCancelledError:
            try:
                output.unlink(missing_ok=True)
            except OSError:
                pass
            now = utc_now()
            self._append_diagnostic(
                job_uuid,
                "job_cancelled",
                "Озвучивание остановлено",
                level="warning",
                stage="Останавливаю…",
            )
            with self.repository.database.transaction() as connection:
                connection.execute(
                    """
                    UPDATE tts_jobs
                    SET status='cancelled', finished_at=?, stage='Отменено',
                        user_error='Озвучивание отменено.', error='',
                        cancel_requested=1, last_activity_at=?
                    WHERE uuid=?
                    """,
                    (now, now, job_uuid),
                )
        except Exception as exc:
            try:
                output.unlink(missing_ok=True)
            except OSError:
                pass
            user_error = self._user_error_for(exc)
            now = utc_now()
            self._append_diagnostic(
                job_uuid,
                "job_failed",
                user_error,
                level="error",
                stage="Фиксирую ошибку",
                exception_type=type(exc).__name__,
                error=str(exc),
            )
            with self.repository.database.transaction() as connection:
                connection.execute(
                    """
                    UPDATE tts_jobs
                    SET status='failed', progress=0, finished_at=?,
                        error=?, user_error=?, stage='Ошибка', last_activity_at=?
                    WHERE uuid=?
                    """,
                    (now, repr(exc), user_error, now, job_uuid),
                )
            log_event(
                "tts_job_failed",
                job_uuid=job_uuid,
                project_uuid=project_uuid,
                chapter_id=chapter["id"],
                error=repr(exc),
            )
        finally:
            with self._lock:
                self._threads.pop(job_uuid, None)
                self._cancel_events.pop(job_uuid, None)

    def _run_batch_worker(
        self, worker_key: str, pending: queue_module.Queue[str]
    ) -> None:
        try:
            while True:
                try:
                    job_uuid = pending.get_nowait()
                except queue_module.Empty:
                    break
                try:
                    self._run_from_database(job_uuid)
                finally:
                    pending.task_done()
        finally:
            with self._lock:
                self._threads.pop(worker_key, None)

    def _run_from_database(self, job_uuid: str) -> None:
        job = self.get_job(job_uuid)
        if job["status"] == "cancelled" or job["cancel_requested"]:
            with self._lock:
                self._cancel_events.pop(job_uuid, None)
            return
        project = self.repository.get_project(
            job["project_uuid"], include_details=True
        )
        chapter = next(
            item for item in project["chapters"] if item["id"] == job["chapter_id"]
        )
        version_number = int(
            Path(job["output_path"]).stem.split("-v", 1)[1].split("-", 1)[0]
        )
        self._run(
            job_uuid,
            job["project_uuid"],
            chapter,
            self._normalized_settings(project.get("tts_settings") or {}),
            job["output_path"],
            version_number,
        )

    @staticmethod
    def _user_error_for(exc: Exception) -> str:
        if isinstance(exc, (ImportError, ModuleNotFoundError, TtsUnavailableError)):
            return TTS_UNAVAILABLE_MESSAGE
        error_name = type(exc).__name__
        if isinstance(exc, (TimeoutError, ConnectionError, OSError)) or error_name in {
            "NoAudioReceived",
            "ConnectionTimeoutError",
            "WSServerHandshakeError",
            "ClientConnectionError",
            "ServerDisconnectedError",
        }:
            return (
                "Сервер озвучки не ответил. Проверьте интернет и VPN, "
                "затем повторите задачу; подробности доступны в диагностике."
            )
        return TTS_FAILED_MESSAGE

    @staticmethod
    def _normalized_settings(settings: dict[str, Any]) -> dict[str, str]:
        values = {
            "voice": str(settings.get("voice") or DEFAULT_VOICE).strip(),
            "rate": str(settings.get("rate") or "+0%").strip(),
            "pitch": str(settings.get("pitch") or "+0Hz").strip(),
            "volume": str(settings.get("volume") or "+0%").strip(),
            "provider": str(settings.get("provider") or "edge-tts").strip(),
        }
        if not RATE_PATTERN.fullmatch(values["rate"]) or values["rate"] not in RATE_VALUES:
            raise ValueError("Выбрано некорректное значение скорости речи.")
        if not PITCH_PATTERN.fullmatch(values["pitch"]) or values["pitch"] not in PITCH_VALUES:
            raise ValueError("Выбрано некорректное значение высоты тона.")
        if not VOLUME_PATTERN.fullmatch(values["volume"]):
            raise ValueError("Выбрано некорректное значение громкости.")
        if values["provider"] != "edge-tts":
            raise ValueError("Выбран неподдерживаемый TTS-провайдер.")
        return values

    @classmethod
    def _synthesize(
        cls,
        text: str,
        output: Path,
        settings: dict[str, str],
        progress_callback: Callable[[int, int, float], None] | None = None,
        event_callback: Callable[[str, str, dict[str, Any]], None] | None = None,
        cancel_check: Callable[[], bool] | None = None,
    ) -> list[dict[str, Any]]:
        chunks = cls._split_text_chunks(text)
        total = len(chunks)

        def report(completed: int, local_fraction: float = 0.0) -> None:
            if progress_callback is None:
                return
            overall = min(1.0, max(0.0, (completed + local_fraction) / total))
            progress_callback(completed, total, overall)

        def event(name: str, message: str, **details: Any) -> None:
            if event_callback is not None:
                event_callback(name, message, details)

        def ensure_not_cancelled() -> None:
            if cancel_check is not None and cancel_check():
                raise TtsCancelledError("Narration cancelled")

        ensure_not_cancelled()
        report(0)
        if len(chunks) == 1:
            event("chunk_started", "Фрагмент 1 из 1: подключение", chunk=1, total=1)
            chunk_options: dict[str, Any] = {
                "progress_callback": lambda fraction: report(0, fraction)
            }
            if event_callback is not None:
                chunk_options["event_callback"] = event_callback
            if cancel_check is not None:
                chunk_options["cancel_check"] = cancel_check
            word_timings = cls._synthesize_chunk(
                chunks[0],
                output,
                settings,
                **chunk_options,
            ) or []
            event("chunk_finished", "Фрагмент 1 из 1 готов", chunk=1, total=1)
            report(1)
            return word_timings

        ffmpeg = shutil.which("ffmpeg")
        if not ffmpeg:
            raise RuntimeError("FFmpeg is required to join long TTS chapters")
        token = uuidlib.uuid4().hex
        parts = [
            output.with_name(f".{output.stem}-{token}-{index:04d}.mp3")
            for index in range(len(chunks))
        ]
        concat_file = output.with_name(f".{output.stem}-{token}-concat.txt")
        word_timings: list[dict[str, Any]] = []
        audio_cursor = 0.0
        try:
            for index, (chunk, part) in enumerate(zip(chunks, parts)):
                ensure_not_cancelled()
                event(
                    "chunk_started",
                    f"Фрагмент {index + 1} из {total}: подключение",
                    chunk=index + 1,
                    total=total,
                )
                chunk_options = {
                    "progress_callback": lambda fraction, done=index: report(
                        done, fraction
                    )
                }
                if event_callback is not None:
                    chunk_options["event_callback"] = event_callback
                if cancel_check is not None:
                    chunk_options["cancel_check"] = cancel_check
                chunk_timings = cls._synthesize_chunk(
                    chunk,
                    part,
                    settings,
                    **chunk_options,
                ) or []
                word_timings.extend(
                    {
                        **timing,
                        "start": round(audio_cursor + float(timing["start"]), 4),
                        "end": round(audio_cursor + float(timing["end"]), 4),
                    }
                    for timing in chunk_timings
                )
                part_duration = cls._audio_duration(part)
                if not part_duration and chunk_timings:
                    part_duration = max(
                        float(timing.get("end") or 0) for timing in chunk_timings
                    )
                audio_cursor += max(0.0, float(part_duration or 0))
                report(index + 1)
                event(
                    "chunk_finished",
                    f"Фрагмент {index + 1} из {total} готов",
                    chunk=index + 1,
                    total=total,
                    bytes=part.stat().st_size if part.is_file() else 0,
                )
            ensure_not_cancelled()
            event("joining_audio", "Склеиваю аудиофрагменты", total=total)
            cls._concat_mp3_files(parts, output, concat_file=concat_file)
            return word_timings
        finally:
            concat_file.unlink(missing_ok=True)
            for part in parts:
                part.unlink(missing_ok=True)

    @staticmethod
    def _concat_mp3_files(
        parts: list[Path],
        output: Path,
        *,
        concat_file: Path | None = None,
    ) -> None:
        ffmpeg = shutil.which("ffmpeg")
        if not ffmpeg:
            raise RuntimeError("FFmpeg is required to join audio files")
        owned_concat_file = concat_file is None
        concat_file = concat_file or output.with_name(
            f".{output.stem}-{uuidlib.uuid4().hex}-concat.txt"
        )
        try:
            concat_file.write_text(
                "\n".join(
                    f"file '{part.resolve().as_posix().replace(chr(39), chr(39) + chr(92) + chr(39) + chr(39))}'"
                    for part in parts
                )
                + "\n",
                encoding="utf-8",
            )
            command = [
                ffmpeg,
                "-y",
                "-f",
                "concat",
                "-safe",
                "0",
                "-i",
                str(concat_file),
                "-c",
                "copy",
                str(output),
            ]
            result = subprocess.run(
                command,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=max(120, len(parts) * 30),
            )
            if result.returncode or not output.is_file() or output.stat().st_size <= 0:
                raise RuntimeError(
                    f"FFmpeg could not join audio files: {(result.stderr or result.stdout)[-800:]}"
                )
        finally:
            if owned_concat_file:
                concat_file.unlink(missing_ok=True)

    @staticmethod
    def _split_text_chunks(text: str, limit: int = TTS_CHUNK_LIMIT) -> list[str]:
        clean = str(text or "").strip()
        if not clean:
            return [""]
        chunks: list[str] = []
        while len(clean) > limit:
            minimum = max(1, int(limit * 0.55))
            window = clean[: limit + 1]
            candidates = [
                window.rfind(separator, minimum, limit + 1)
                for separator in ("\n\n", ". ", "! ", "? ", "; ", ", ", " ")
            ]
            cut = max(candidates)
            if cut < minimum:
                cut = limit
            elif window[cut : cut + 2] in {". ", "! ", "? ", "; ", ", "}:
                cut += 1
            chunk = clean[:cut].strip()
            if chunk:
                chunks.append(chunk)
            clean = clean[cut:].strip()
        if clean:
            chunks.append(clean)
        return chunks or [""]

    @staticmethod
    def _synthesize_chunk(
        text: str,
        output: Path,
        settings: dict[str, str],
        progress_callback: Callable[[float], None] | None = None,
        event_callback: Callable[[str, str, dict[str, Any]], None] | None = None,
        cancel_check: Callable[[], bool] | None = None,
    ) -> list[dict[str, Any]]:
        import edge_tts

        def event(name: str, message: str, **details: Any) -> None:
            if event_callback is not None:
                event_callback(name, message, details)

        def ensure_not_cancelled() -> None:
            if cancel_check is not None and cancel_check():
                raise TtsCancelledError("Narration cancelled")

        async def stream_to_file(communicate: Any) -> list[dict[str, Any]]:
            spoken_characters = 0
            received_bytes = 0
            first_audio_received = False
            word_timings: list[dict[str, Any]] = []
            with output.open("wb") as audio:
                async for message in communicate.stream():
                    ensure_not_cancelled()
                    if message["type"] == "audio":
                        audio.write(message["data"])
                        received_bytes += len(message["data"])
                        if not first_audio_received:
                            first_audio_received = True
                            event(
                                "audio_started",
                                "Соединение установлено, получаю аудио",
                            )
                    elif message["type"] in {"WordBoundary", "SentenceBoundary"}:
                        if message["type"] == "WordBoundary":
                            start = max(0.0, float(message.get("offset") or 0) / 10_000_000)
                            boundary_duration = max(
                                0.0,
                                float(message.get("duration") or 0) / 10_000_000,
                            )
                            word_timings.append(
                                {
                                    "text": str(message.get("text") or ""),
                                    "start": round(start, 4),
                                    "end": round(start + boundary_duration, 4),
                                }
                            )
                        spoken_characters += len(str(message.get("text") or "")) + 1
                        if progress_callback:
                            progress_callback(
                                min(0.97, spoken_characters / max(1, len(text)))
                            )
                audio.flush()
            event("audio_received", "Аудиофрагмент получен", bytes=received_bytes)
            return word_timings

        last_error: Exception | None = None
        for attempt in range(TTS_RETRY_ATTEMPTS):
            ensure_not_cancelled()
            try:
                event(
                    "network_attempt",
                    f"Подключение к TTS: попытка {attempt + 1} из {TTS_RETRY_ATTEMPTS}",
                    attempt=attempt + 1,
                    attempts=TTS_RETRY_ATTEMPTS,
                    connect_timeout=TTS_CONNECT_TIMEOUT_SECONDS,
                    receive_timeout=TTS_RECEIVE_TIMEOUT_SECONDS,
                )
                communicate = edge_tts.Communicate(
                    text,
                    settings["voice"],
                    rate=settings["rate"],
                    volume=settings["volume"],
                    pitch=settings["pitch"],
                    boundary="WordBoundary",
                    connect_timeout=TTS_CONNECT_TIMEOUT_SECONDS,
                    receive_timeout=TTS_RECEIVE_TIMEOUT_SECONDS,
                )
                word_timings = asyncio.run(stream_to_file(communicate))
                if output.is_file() and output.stat().st_size > 0:
                    if progress_callback:
                        progress_callback(1.0)
                    return word_timings
                raise RuntimeError("TTS provider did not create an audio file")
            except TtsCancelledError:
                output.unlink(missing_ok=True)
                raise
            except Exception as exc:
                last_error = exc
                output.unlink(missing_ok=True)
                transient = isinstance(exc, (TimeoutError, ConnectionError, OSError)) or type(exc).__name__ in {
                    "NoAudioReceived",
                    "ConnectionTimeoutError",
                    "WSServerHandshakeError",
                    "ClientConnectionError",
                    "ServerDisconnectedError",
                }
                final_attempt = attempt == TTS_RETRY_ATTEMPTS - 1
                event(
                    "network_error" if final_attempt or not transient else "network_retry",
                    (
                        "TTS не ответил; повторяю подключение"
                        if transient and not final_attempt
                        else "Ошибка соединения с TTS"
                    ),
                    attempt=attempt + 1,
                    transient=transient,
                    exception_type=type(exc).__name__,
                    error=str(exc),
                )
                if not transient or final_attempt:
                    raise
                deadline = time.monotonic() + attempt + 1
                while time.monotonic() < deadline:
                    ensure_not_cancelled()
                    time.sleep(min(0.1, deadline - time.monotonic()))
        if last_error:
            raise last_error

    @staticmethod
    def _audio_duration(path: Path) -> float | None:
        ffprobe = shutil.which("ffprobe")
        if not ffprobe:
            return None
        try:
            result = subprocess.run(
                [
                    ffprobe,
                    "-v",
                    "error",
                    "-show_entries",
                    "format=duration",
                    "-of",
                    "default=noprint_wrappers=1:nokey=1",
                    str(path),
                ],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=20,
            )
            value = float(result.stdout.strip()) if result.returncode == 0 else 0
            return round(value, 3) if value > 0 else None
        except (OSError, ValueError, subprocess.TimeoutExpired):
            return None

    @staticmethod
    def _absolute_project_path(project: dict[str, Any], relative_path: str) -> Path:
        projects_root = (USER_DATA / "projects").resolve()
        project_root = (USER_DATA / project["project_folder"]).resolve()
        output = (USER_DATA / relative_path).resolve()
        try:
            project_root.relative_to(projects_root)
            output.relative_to(project_root)
        except ValueError as exc:
            raise ValueError("Некорректный путь файла озвучки.") from exc
        return output
