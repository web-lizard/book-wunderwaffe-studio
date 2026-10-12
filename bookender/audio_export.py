"""Bounded, audio-only book rendering using the editor's existing music mix."""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
import uuid
import zipfile
from pathlib import Path
from typing import Callable

from suviren_q import hidden_process_options, render_audio_master


def validate_audio(path: Path, duration: float) -> None:
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path)],
        capture_output=True, text=True, encoding="utf-8", check=True,
        timeout=60, **hidden_process_options(),
    )
    probe = json.loads(result.stdout)
    streams = probe.get("streams", [])
    actual = float(probe.get("format", {}).get("duration") or 0)
    if (not streams or any(stream.get("codec_type") != "audio" for stream in streams)
            or abs(actual - duration) > max(0.15, duration * 0.001)):
        raise ValueError("Проверка аудио не пройдена: неверная длительность или состав дорожек.")


def render_book_audio(
    chapters: list[dict], *, export_dir: Path, title: str, mode: str,
    music_config: dict, music_clips: list[dict] | None, music: Path | None,
    bitrate: int = 192, progress: Callable[[float, str], None] = lambda *_: None,
) -> Path:
    """Render selected chapters in book order; encode a joined MP3 only once."""
    if mode not in {"complete", "chapters"} or not chapters:
        raise ValueError("Выберите хотя бы одну главу и формат экспорта.")
    if bitrate not in {128, 192, 256, 320}:
        raise ValueError("Допустимый битрейт MP3: 128, 192, 256 или 320 кбит/с.")
    if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
        raise ValueError("Для экспорта нужны FFmpeg и FFprobe.")
    export_dir.mkdir(parents=True, exist_ok=True)
    total = sum(chapter["duration"] for chapter in chapters)
    # FLAC never requires more than the uncompressed stereo PCM budget here.
    required = total * (48000 * 4 + bitrate * 125) + 64 * 1024**2
    if shutil.disk_usage(export_dir).free < required:
        raise ValueError("Недостаточно свободного места для сборки аудиокниги.")
    extension = "mp3" if mode == "complete" else "zip"
    output = export_dir / f"{title} - {uuid.uuid4().hex[:8]}.{extension}"
    with tempfile.TemporaryDirectory(prefix=".audio-export-", dir=export_dir) as temporary:
        folder = Path(temporary)
        files = []
        done = 0.0
        for index, chapter in enumerate(chapters):
            progress(done / total * 0.85, f"Глава {index + 1}/{len(chapters)}: {chapter['title']}")
            path = folder / f"{index:05d}.{'flac' if mode == 'complete' else 'mp3'}"
            render_audio_master(
                chapter["audio"], path, start=chapter["start"], duration=chapter["duration"],
                narration_chapters=[{
                    "start_seconds": chapter["start"],
                    "end_seconds": chapter["start"] + chapter["duration"],
                    "sourceStartSeconds": 0,
                    "sourceEndSeconds": chapter["source_duration"],
                }],
                music=music, music_config=music_config, music_clips=music_clips,
                audio_bitrate=f"{bitrate}k",
            )
            validate_audio(path, chapter["duration"])
            files.append(path)
            done += chapter["duration"]
        progress(0.86, "Сборка MP3" if mode == "complete" else "Сборка архива")
        result = folder / f"result.{extension}"
        if mode == "complete":
            manifest = folder / "concat.txt"
            # Generated relative ASCII names avoid quoting arbitrary user paths.
            manifest.write_text("".join(f"file '{path.name}'\n" for path in files), encoding="utf-8")
            process = subprocess.run(
                ["ffmpeg", "-y", "-nostdin", "-v", "error", "-f", "concat", "-safe", "1",
                 "-i", str(manifest), "-vn", "-c:a", "libmp3lame", "-b:a", f"{bitrate}k",
                 "-ar", "48000", "-ac", "2", "-metadata", f"title={title}", str(result)],
                capture_output=True, text=True, encoding="utf-8", errors="replace",
                **hidden_process_options(),
            )
            if process.returncode:
                raise ValueError(f"FFmpeg не собрал MP3: {process.stderr[-1500:]}")
            validate_audio(result, total)
        else:
            with zipfile.ZipFile(result, "w", compression=zipfile.ZIP_STORED) as archive:
                for chapter, path in zip(chapters, files):
                    archive.write(path, f"{chapter['position']:03d} {chapter['title']}.mp3")
                archive.writestr("chapters.json", json.dumps([
                    {key: chapter[key] for key in ("id", "position", "title", "duration")}
                    for chapter in chapters
                ], ensure_ascii=False, indent=2))
        progress(0.98, "Проверка завершена")
        os.replace(result, output)
    return output
