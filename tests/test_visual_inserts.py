from __future__ import annotations

import copy
import json
import math
import shutil
import struct
import subprocess
import tempfile
import unittest
import wave
from pathlib import Path
from unittest.mock import patch

from bookender.visual_inserts import auto_place_visuals, compile_scene_plan, normalize_visual_inserts, scene_at


def project_fixture():
    return {"duration_seconds": 600, "materials": [{"id": "art", "type": "image"}, {"id": "art2", "type": "image"}],
            "chapters": [{"id": "one", "title": "One", "start_seconds": 0, "end_seconds": 300},
                         {"id": "two", "title": "Two", "start_seconds": 300, "end_seconds": 600}]}


class VisualScheduleTests(unittest.TestCase):
    def test_old_project_is_normal_and_unchanged(self):
        project = project_fixture()
        before = copy.deepcopy(project)
        plan = compile_scene_plan(project)
        self.assertEqual(plan["clips"], [])
        self.assertEqual(scene_at(plan, 42)["mode"], "normal")
        self.assertEqual(project, before)
        self.assertEqual(normalize_visual_inserts(None), [])

    def test_one_visual_and_random_access_crossfades(self):
        project = project_fixture()
        project["chapters"][0]["visualInserts"] = [{"assetId": "art", "startTime": 30, "duration": 180}]
        plan = compile_scene_plan(project)
        for time, mode, opacity in [(0, "normal", 0), (30, "chapter_visual", 0),
                                    (30.375, "chapter_visual", .5), (100, "chapter_visual", 1),
                                    (209.625, "chapter_visual", .5), (210, "normal", 0), (600, "normal", 0)]:
            with self.subTest(time=time):
                scene = scene_at(plan, time)
                self.assertEqual(scene["mode"], mode)
                self.assertAlmostEqual(scene["opacity"], opacity)

    def test_boundary_is_clipped_and_next_chapter_owns_exact_boundary(self):
        project = project_fixture()
        project["chapters"][0]["visualInserts"] = [{"assetId": "art", "startTime": 290, "duration": 100}]
        project["chapters"][1]["visualInserts"] = [{"assetId": "art2", "startTime": 0, "duration": 10}]
        plan = compile_scene_plan(project)
        self.assertEqual(plan["clips"][0]["end"], 300)
        self.assertEqual(scene_at(plan, 300)["visual"]["assetId"], "art2")
        self.assertEqual(scene_at(plan, 310)["mode"], "normal")

    def test_multiple_and_overlapping_inserts_are_deterministic(self):
        project = project_fixture()
        project["chapters"][0]["visualInserts"] = [
            {"id": "a", "assetId": "art", "startTime": 10, "duration": 20},
            {"id": "b", "assetId": "art2", "startTime": 20, "duration": 20},
            {"id": "c", "assetId": "art", "startTime": 50, "duration": 10, "transition": "none"},
        ]
        plan = compile_scene_plan(project)
        self.assertEqual([(c["start"], c["end"]) for c in plan["clips"]], [(10, 30), (30, 40), (50, 60)])
        self.assertEqual(scene_at(plan, 30)["visual"]["id"], "b")
        self.assertEqual(scene_at(plan, 45)["mode"], "normal")
        self.assertEqual(scene_at(plan, 50)["opacity"], 1)

    def test_auto_single_multiple_short_and_append(self):
        chapter = {"id": "one", "imageAssetId": "art"}
        single = auto_place_visuals(chapter, 600)
        self.assertEqual((single[0]["startTime"], single[0]["duration"]), (30, 180))
        for length in [0.1, 1, 5, 20, 90, 600]:
            for count in [1, 2, 5]:
                clips = auto_place_visuals(chapter, length, asset_ids=["art"] * count)
                for left, right in zip(clips, clips[1:]):
                    self.assertLess(left["startTime"] + left["duration"], right["startTime"])
                for clip in clips:
                    self.assertGreater(clip["duration"], 0)
                    self.assertLess(clip["startTime"] + clip["duration"], length)
        chapter["visualInserts"] = single
        appended = auto_place_visuals(chapter, 600, asset_ids=["art2"], append=True)
        self.assertEqual(appended[0], single[0])
        self.assertGreater(appended[1]["startTime"], 210)
        self.assertEqual(len({c["id"] for c in appended}), 2)

    def test_bad_data_and_missing_assets_never_cover_the_normal_scene(self):
        project = project_fixture()
        project["chapters"][0]["visualInserts"] = [None,
            {"assetId": "art", "startTime": float("nan"), "duration": float("inf")},
            {"assetId": "missing", "startTime": 20, "duration": 20},
            {"assetId": "art", "type": "future-comic", "startTime": 30, "duration": 20},
        ]
        plan = compile_scene_plan(project)
        self.assertEqual(plan["clips"], [])
        self.assertEqual(len(plan["issues"]), 3)

    def test_save_api_sqlite_and_reopen_preserve_visuals(self):
        from bookender.database import BookenderDatabase
        from bookender.repository import ProjectRepository
        from suviren_q_server import save_editor_project, editor_scene_plan

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            database = BookenderDatabase(root / "bookender.db", Path(__file__).resolve().parents[1] / "bookender/migrations")
            repository = ProjectRepository(database)
            with patch("bookender.repository.USER_DATA", root), patch("suviren_q_server.PROJECT_REPOSITORY", repository), \
                 patch("suviren_q_server.CHAPTERS_PATH", root / "chapters.json"), \
                 patch("suviren_q_server.EDITOR_PROJECT_PATH", root / "editor.json"):
                repository.initialize()
                book = repository.create_project(title="Visual test")
                payload = project_fixture()
                payload["chapters"][0]["visualInserts"] = auto_place_visuals(payload["chapters"][0], 300, asset_ids=["art", "art2"])
                saved = save_editor_project(payload, project_uuid=book["uuid"], edition_id=None)
                reopened = repository.get_video_edition(book["uuid"], saved["edition_id"])["settings"]
                self.assertEqual(reopened["chapters"][0]["visualInserts"], saved["project"]["chapters"][0]["visualInserts"])
                self.assertEqual(reopened["chapters"][1]["visualInserts"], [])
                self.assertEqual(editor_scene_plan(reopened), compile_scene_plan(payload))
                self.assertEqual(json.loads((root / "editor.json").read_text(encoding="utf-8"))["chapters"], reopened["chapters"])


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "FFmpeg required")
class VisualRenderTests(unittest.TestCase):
    def test_real_export_crossfades_keeps_chrome_audio_and_seeks_inside_insert(self):
        from PIL import Image, ImageChops, ImageStat
        from bookender.visual_render import prepare_visual_content
        from suviren_q import Chapter, draw_panel, _render_segment_worker, mux_master_audio, concat_segments

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            width, height = 640, 360
            source = root / "portrait.png"
            Image.new("RGB", (160, 320), (20, 220, 40)).save(source)
            voice = root / "voice.wav"
            with wave.open(str(voice), "wb") as audio:
                audio.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
                audio.writeframes(b"".join(struct.pack("<h", round(2500 * math.sin(i * 330 * math.tau / 16000))) for i in range(8 * 16000)))
            project = {"duration_seconds": 8, "materials": [{"id": "art", "type": "image", "serverPath": str(source)}],
                       "glitch": False, "layers": {"visualizer": {"visible": False}},
                       "chapters": [{"id": "one", "title": "Illustration", "start_seconds": 0, "end_seconds": 6,
                                     "visualInserts": [{"assetId": "art", "startTime": 1, "duration": 3}]},
                                    {"id": "two", "title": "Normal again", "start_seconds": 6, "end_seconds": 8}]}
            chapters = [Chapter(ch["title"], ch["start_seconds"], ch["end_seconds"]) for ch in project["chapters"]]
            clips = prepare_visual_content(compile_scene_plan(project), project, root, width=width, height=height)
            normal, chrome = root / "normal.png", root / "chrome.png"
            draw_panel(normal, chapters, 0, None, width=width, height=height, animated_visuals=True,
                       editor_project=project, chrome_out=chrome)
            base = {"panel": str(normal), "chrome": str(chrome), "audio": str(voice), "start": 0, "duration": 6,
                    "fps": 24, "width": width, "height": height, "gpu_codec": "libx264", "gpu_preset": "ultrafast",
                    "visualizer": {"visible": False}, "timeline_duration": 8, "waveform": "ffmpeg", "include_audio": False,
                    "visual_inserts": clips, "captions": {"enabled": True}, "caption_text": "The current narrated passage stays behind the illustration."}
            full = root / "full.mp4"
            rendered = _render_segment_worker({**base, "out": str(full)})
            self.assertTrue(rendered["ok"], rendered.get("error"))
            inside = root / "inside.mp4"
            rendered = _render_segment_worker({**base, "start": 1.375, "duration": 3.5, "out": str(inside)})
            self.assertTrue(rendered["ok"], rendered.get("error"))
            after = root / "after.mp4"
            rendered = _render_segment_worker({**base, "visual_inserts": [], "start": 6, "duration": 2, "out": str(after)})
            self.assertTrue(rendered["ok"], rendered.get("error"))

            def frame(path, time):
                data = subprocess.run(["ffmpeg", "-v", "error", "-ss", str(time), "-i", str(path), "-frames:v", "1",
                                       "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], capture_output=True, check=True).stdout
                return Image.frombytes("RGB", (width, height), data)

            before, fade_in, visual, fade_out, returned = [frame(full, time) for time in [.5, 1.375, 2, 3.625, 4.5]]
            sample = (width // 2, height // 3)
            green = [im.getpixel(sample)[1] for im in [before, fade_in, visual, fade_out, returned]]
            self.assertGreater(green[2], green[0] + 90, green)
            self.assertTrue(green[0] + 15 < green[1] < green[2] - 15, green)
            self.assertTrue(green[4] + 15 < green[3] < green[2] - 15, green)
            self.assertAlmostEqual(green[0], green[4], delta=5)
            self.assertAlmostEqual(frame(inside, 0).getpixel(sample)[1], green[1], delta=5)
            self.assertAlmostEqual(frame(inside, .625).getpixel(sample)[1], green[2], delta=5)
            self.assertAlmostEqual(frame(inside, 3.125).getpixel(sample)[1], green[4], delta=5)
            progress_sample = (round(width * .52), round(height * .944))
            self.assertNotEqual(before.getpixel(progress_sample), visual.getpixel(progress_sample))
            # QR's opaque modules must be unchanged through a transition.
            alpha = Image.open(chrome).getchannel("A")
            opaque = alpha.point(lambda a: 255 if a == 255 else 0)
            self.assertGreater(sum(opaque.tobytes()), 1000)
            diff = ImageChops.difference(before, visual)
            self.assertLess(max(ImageStat.Stat(diff, opaque).mean), 8)
            # The final mux covers both chapter segments with one continuous AAC stream.
            combined, final = root / "combined.mp4", root / "result.mp4"
            concat_segments([full, after], combined, root / "concat.txt")
            mux_master_audio(combined, voice, final, duration=8)
            probe = json.loads(subprocess.run(["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(final)], capture_output=True, text=True, check=True).stdout)
            self.assertEqual([s["codec_type"] for s in probe["streams"]], ["video", "audio"])
            self.assertAlmostEqual(float(probe["format"]["duration"]), 8, delta=.15)
            self.assertAlmostEqual(float(probe["streams"][1]["duration"]), 8, delta=.1)

            # Exercise the complete CLI path as well as the segment adapter:
            # assets -> compiler -> panels -> workers -> concat -> master mux.
            from suviren_q import main
            project["layers"]["visualizer"]["visible"] = True
            project["captions"] = {"enabled": True}
            project["chapters"][0]["text"] = "A narrated chapter with two pictures."
            project["chapters"][0]["visualInserts"].append({"assetId": "art", "startTime": 4.8, "duration": .8})
            project["chapters"][1]["visualInserts"] = [{"assetId": "art", "startTime": 0, "duration": 1.5}]
            editor_path, chapter_path = root / "editor.json", root / "chapters.json"
            editor_path.write_text(json.dumps(project), encoding="utf-8")
            chapter_path.write_text(json.dumps(project["chapters"]), encoding="utf-8")
            output = root / "pipeline.mp4"
            with patch("suviren_q.detect_gpu_encoder", return_value=("CPU", {"codec": "libx264", "preset": "ultrafast", "label": "Test CPU"})):
                main(["render", "--audio", str(voice), "--chapters", str(chapter_path),
                      "--editor-project", str(editor_path), "--out", str(output), "--build-dir", str(root),
                      "--width", "640", "--height", "360", "--fps", "24", "--no-parallel"])
            self.assertAlmostEqual(frame(output, 2).getpixel(sample)[1], green[2], delta=8)
            self.assertAlmostEqual(frame(output, 6.75).getpixel(sample)[1], green[2], delta=8)


if __name__ == "__main__":
    unittest.main()
