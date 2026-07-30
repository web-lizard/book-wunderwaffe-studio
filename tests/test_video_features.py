from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from suviren_q import (
    VISUALIZER_COLOR_PRESETS,
    _prepare_panel_background,
    build_music_mix_filter,
    build_music_timeline_filter,
    escape_drawtext_text,
    load_chapters,
    mux_master_audio,
    reading_caption_chunks,
    visualizer_preset_colors,
)


class VideoBookFeatureTests(unittest.TestCase):
    def test_caption_chunks_cover_the_whole_chapter(self) -> None:
        chunks = reading_caption_chunks(
            " ".join(f"слово{index}" for index in range(31)),
            12.0,
            words_per_card=10,
        )
        self.assertEqual(len(chunks), 4)
        self.assertEqual(chunks[0][0], 0)
        self.assertEqual(chunks[-1][1], 12.0)
        self.assertTrue(all(start < end for start, end, _ in chunks))
        self.assertIn(r"\:", escape_drawtext_text("реплика: тест"))

    def test_caption_wrap_width_follows_the_movable_layer(self) -> None:
        text = " ".join(["длинныйфрагмент"] * 12)
        narrow = reading_caption_chunks(
            text,
            4.0,
            words_per_card=12,
            line_width=18,
        )
        wide = reading_caption_chunks(
            text,
            4.0,
            words_per_card=12,
            line_width=120,
        )
        self.assertGreater(narrow[0][2].count("\n"), wide[0][2].count("\n"))

    def test_music_mix_has_volume_three_band_eq_and_limiter(self) -> None:
        audio_filter = build_music_mix_filter(
            {"volume": 0.22, "bass": 3, "mid": -2, "treble": 4},
            20.0,
        )
        self.assertIn("volume=0.2200", audio_filter)
        self.assertIn("f=120:t=q:w=1:g=3.00", audio_filter)
        self.assertIn("f=1100:t=q:w=1:g=-2.00", audio_filter)
        self.assertIn("f=7000:t=q:w=1:g=4.00", audio_filter)
        self.assertIn("amix=inputs=2", audio_filter)
        self.assertIn("alimiter=limit=0.95", audio_filter)

    def test_music_mux_loops_and_maps_the_mixed_audio(self) -> None:
        with patch("suviren_q.run_cmd") as run:
            mux_master_audio(
                Path("video.mp4"),
                Path("voice.mp3"),
                Path("result.mp4"),
                music=Path("music.flac"),
                music_config={"enabled": True, "loop": True, "volume": 0.16},
                duration=30.0,
                dry_run=True,
            )
        command = run.call_args.args[0]
        self.assertIn("-stream_loop", command)
        self.assertIn("music.flac", command)
        self.assertIn("-filter_complex", command)
        self.assertIn("[mixed]", command)

    def test_music_timeline_positions_and_mixes_independent_clips(self) -> None:
        clips = [
            {"duration": 8.0, "timeline_start": 0.0, "volume": 1.0},
            {"duration": 6.0, "timeline_start": 12.5, "volume": 0.5},
        ]
        audio_filter = build_music_timeline_filter(
            {"volume": 0.2, "bass": 2, "mid": 0, "treble": -1},
            clips,
            30.0,
        )
        self.assertIn("[2:a]", audio_filter)
        self.assertIn("[3:a]", audio_filter)
        self.assertIn("adelay=12500:all=1", audio_filter)
        self.assertIn("volume=0.1000", audio_filter)
        self.assertIn("amix=inputs=3", audio_filter)

    def test_music_timeline_mux_trims_clips_to_render_window(self) -> None:
        with patch("suviren_q.run_cmd") as run:
            mux_master_audio(
                Path("video.mp4"),
                Path("voice.mp3"),
                Path("result.mp4"),
                music_config={"enabled": True, "loop": True, "volume": 0.16},
                music_clips=[
                    {
                        "path": Path("chapter-one.flac"),
                        "start": 5.0,
                        "end": 15.0,
                        "sourceOffset": 2.0,
                        "volume": 1.0,
                    },
                    {
                        "path": Path("outside.flac"),
                        "start": 40.0,
                        "end": 50.0,
                    },
                ],
                start=10.0,
                duration=20.0,
                dry_run=True,
            )
        command = run.call_args.args[0]
        self.assertIn("chapter-one.flac", command)
        self.assertNotIn("outside.flac", command)
        self.assertIn("7.000000", command)
        self.assertIn("-filter_complex", command)

    def test_chapter_json_preserves_text_and_image(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "chapters.json"
            path.write_text(
                json.dumps(
                    [
                        {
                            "title": "Глава",
                            "start_seconds": 0,
                            "end_seconds": 3,
                            "text": "Текст на экране",
                            "image_path": "chapter.png",
                        }
                    ],
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            chapter = load_chapters(path)[0]
        self.assertEqual(chapter.text, "Текст на экране")
        self.assertEqual(chapter.image_path, "chapter.png")

    def test_visualizer_has_twelve_export_safe_color_presets(self) -> None:
        self.assertEqual(len(VISUALIZER_COLOR_PRESETS), 12)
        ocean = visualizer_preset_colors({"colorPreset": "ocean"}, (1, 2, 3))
        self.assertEqual(ocean[0], (124, 231, 255))
        fallback = visualizer_preset_colors({"colorPreset": "unknown"}, (1, 2, 3))
        self.assertEqual(fallback[0], (255, 173, 39))

    def test_background_blur_can_be_disabled_for_export(self) -> None:
        from PIL import Image

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "backdrop.png"
            source = Image.new("RGB", (24, 24), "white")
            for x in range(12):
                for y in range(24):
                    source.putpixel((x, y), (0, 0, 0))
            source.save(path)
            blurred = _prepare_panel_background(
                path,
                (24, 24),
                (0, 0, 0),
                glitch=False,
                blur=True,
            )
            sharp = _prepare_panel_background(
                path,
                (24, 24),
                (0, 0, 0),
                glitch=False,
                blur=False,
            )
        self.assertNotEqual(blurred.tobytes(), sharp.tobytes())


if __name__ == "__main__":
    unittest.main()
