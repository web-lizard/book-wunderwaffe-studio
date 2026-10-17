"""Assemble chapters 0-8 of the Sun Merchant Comic Edition.

The operation is deliberately scoped to the dedicated duplicate project. It is
idempotent: re-running it replaces only generated comic-frame records and the
edition's chapter 0-8 visual schedules.
"""

from __future__ import annotations

import json
from pathlib import Path

from bookender.paths import USER_DATA
from bookender.repository import ProjectRepository, json_text, utc_now
from bookender.tts import TtsService
from bookender.visual_inserts import compile_scene_plan


PROJECT_UUID = "d1e0570e-03d8-4163-8312-7a6b41e2ddaa"
EDITION_ID = 7
CHAPTER_IDS = (123, 124, 125, 126, 127, 128, 129, 130, 131)
GLOBAL_PLAYBACK_RATE = 1.18
BEAT_STARTS = {
    0: (0.0, 27.1, 54.2, 82.2, 110.2, 139.0),
    1: (0.0, 45.0, 89.0, 134.0, 178.0, 220.0, 262.7, 310.0,
        360.2, 400.0, 440.7, 485.0, 529.7, 575.0, 622.9, 680.0),
    2: (0.0, 60.0, 118.6, 175.0, 233.1, 285.0, 339.0, 390.0, 444.9,
        500.0, 559.3, 620.0, 686.4, 745.0, 809.3, 870.0, 940.0, 990.0,
        1035.0),
    # Chapter 3 starts are anchored to exact WordBoundary events from audio
    # asset 883, then converted to the edition's 1.18x playback timeline.
    3: (0.0, 60.8719, 106.4798, 124.9120, 184.1734, 202.5893,
        235.8764, 266.8168, 309.6864, 326.4563, 355.9298, 364.4452,
        416.8816, 440.5290, 489.2714, 523.2919, 555.1531, 579.3056,
        605.9353, 650.0764, 700.6795, 737.4542, 772.2081, 806.6388,
        821.8035, 849.4436, 885.9250),
    4: (0.0, 40.0, 85.6, 120.0, 155.2, 220.0, 260.0, 296.4, 332.7,
        380.0, 402.7, 420.5, 453.0, 496.2, 519.1, 552.0, 575.0, 610.0,
        632.9, 650.8, 700.0, 736.8, 774.0, 806.0, 822.0),
    5: (0.0, 32.0, 64.0, 83.0, 108.5, 165.0, 190.0, 220.3, 245.0,
        275.4, 330.5, 358.0, 395.0, 423.0, 440.7, 480.0, 523.0, 550.8,
        575.0, 605.9, 633.0),
    6: (0.0, 52.5, 134.4, 174.6, 219.6, 270.0, 290.7, 346.0, 360.6,
        397.1, 445.6, 543.0, 613.1, 712.4, 751.8, 772.5, 864.5, 917.2,
        937.1, 951.2, 984.3, 1013.5, 1134.7, 1197.3, 1209.5, 1306.3,
        1367.5, 1404.0),
    7: (0.0, 55.0, 100.0, 145.0, 200.0, 255.0, 305.0, 360.0, 420.0,
        475.0, 525.0, 575.0, 635.0, 715.0, 780.0, 820.0, 865.0, 915.0,
        960.0, 1010.0, 1065.0, 1110.0, 1155.0, 1200.0, 1245.0, 1290.0,
        1345.0, 1395.0, 1435.0, 1485.0, 1535.0, 1585.0),
    8: (0.0, 55.0, 110.0, 145.0, 205.0, 260.0, 315.0, 345.0, 375.0,
        450.0, 505.0, 550.0, 570.0, 610.0, 655.0, 700.0, 755.0, 810.0,
        840.0, 890.0, 945.0, 995.0, 1035.0, 1080.0, 1120.0, 1165.0,
        1245.0, 1275.0, 1310.0, 1390.0),
}


def relative_image_path(chapter_index: int, frame_index: int) -> str:
    return (
        f"projects/{PROJECT_UUID}/images/comic/"
        f"chapter-{chapter_index}-{frame_index:02d}.png"
    )


def format_timecode(seconds: float) -> str:
    total_milliseconds = max(0, round(float(seconds) * 1000))
    hours, remainder = divmod(total_milliseconds, 3_600_000)
    minutes, remainder = divmod(remainder, 60_000)
    whole_seconds, milliseconds = divmod(remainder, 1000)
    return f"{hours:02d}:{minutes:02d}:{whole_seconds:02d}.{milliseconds:03d}"


def build() -> dict:
    repository = ProjectRepository()
    repository.initialize()
    project = repository.get_project(PROJECT_UUID, include_details=True)
    mapped_chapter_ids = tuple(
        int(chapter["id"])
        for chapter in project["chapters"]
        if int(chapter["id"]) in CHAPTER_IDS
    )
    if mapped_chapter_ids != CHAPTER_IDS:
        raise RuntimeError("Comic Edition chapter mapping changed; refusing to edit another project")

    edition = repository.get_video_edition(PROJECT_UUID, EDITION_ID)
    settings = edition["settings"]
    master = TtsService(repository).build_video_master(PROJECT_UUID)
    master_timings = {
        int(chapter["chapter_id"]): chapter for chapter in master["chapters"]
    }
    book_chapters = {int(chapter["id"]): chapter for chapter in project["chapters"]}
    settings_chapters = settings.setdefault("chapters", [])
    existing_chapter_ids = {
        int(chapter.get("chapterId") or 0) for chapter in settings_chapters
    }
    for chapter_id in CHAPTER_IDS:
        if chapter_id in existing_chapter_ids:
            continue
        source = book_chapters[chapter_id]
        settings_chapters.append({
            "id": f"chapter-{chapter_id}",
            "chapterId": chapter_id,
            "title": source["title"],
            "text": source["reader_content"],
            "isZero": bool(source.get("is_zero")),
            "source": "editor",
            "sourceContentHash": source["narration_hash"],
            "imageAssetId": None,
            "audioAssetId": None,
            "visualInserts": [],
        })
    project_positions = {
        int(chapter["id"]): int(chapter["position"])
        for chapter in project["chapters"]
    }
    settings_chapters.sort(
        key=lambda chapter: project_positions.get(
            int(chapter.get("chapterId") or 0), 1_000_000
        )
    )
    chapters_by_database_id = {
        int(chapter.get("chapterId")): chapter for chapter in settings_chapters
    }

    timeline_cursor = 0.0
    for chapter in settings_chapters:
        chapter_id = int(chapter.get("chapterId") or 0)
        timing = master_timings.get(chapter_id)
        source = book_chapters.get(chapter_id)
        if timing is None or source is None:
            continue
        chapter["playbackRate"] = GLOBAL_PLAYBACK_RATE
        playback_rate = GLOBAL_PLAYBACK_RATE
        source_start = float(timing["start_seconds"])
        source_end = float(timing["end_seconds"])
        chapter["title"] = source["title"]
        chapter["text"] = source["reader_content"]
        chapter["sourceContentHash"] = source["narration_hash"]
        chapter["start_seconds"] = timeline_cursor
        timeline_cursor += (source_end - source_start) / playback_rate
        chapter["end_seconds"] = timeline_cursor
        chapter["sourceStartSeconds"] = source_start
        chapter["sourceEndSeconds"] = source_end
        chapter["audioAssetId"] = f"chapter-audio-{timing['audio_asset_id']}"
        chapter["wordTimings"] = timing.get("word_timings", [])
        chapter["start"] = format_timecode(chapter["start_seconds"])
        chapter["end"] = format_timecode(chapter["end_seconds"])
        chapter["duration_seconds"] = round(
            chapter["end_seconds"] - chapter["start_seconds"], 3
        )

    settings["materials"] = [
        item for item in settings.get("materials", [])
        if item.get("role") != "book-master"
    ]
    master_id = f"book-master-{'-'.join(str(value) for value in master['source_asset_ids'])}"
    settings["materials"].append({
        "id": master_id,
        "type": "audio",
        "name": "Торговец солнцем · Comic Edition · мастер.mp3",
        "size": int(master["file_size"]),
        "serverPath": master["file_path"],
        "status": "ready",
        "progress": 1,
        "role": "book-master",
    })
    settings["audioAssetId"] = master_id
    if len(settings.get("scenes", [])) == 1:
        settings["scenes"][0]["start"] = 0
        settings["scenes"][0]["end"] = timeline_cursor

    settings["presentationMode"] = "comic"
    settings["glitch"] = False
    settings["backgroundBlur"] = False
    settings["captions"] = {"enabled": True, "wordsPerCard": 16}
    settings["comic"] = {
        "version": 1,
        "chapterTitleSeconds": 8,
        "controlsAutoHideMs": 2200,
        "kenBurns": True,
        "noirFilter": True,
        "scope": "chapters-0-8",
        "playbackRate": GLOBAL_PLAYBACK_RATE,
        "storyboard": "COMIC_EDITION_STORYBOARD.md",
    }
    settings.setdefault("layers", {}).setdefault("visualizer", {})["visible"] = False

    materials = [
        item for item in settings.get("materials", [])
        if item.get("role") != "comic-frame"
    ]
    visual_rows = []
    for chapter_index, chapter_id in enumerate(CHAPTER_IDS):
        chapter = chapters_by_database_id[chapter_id]
        duration = float(chapter["end_seconds"]) - float(chapter["start_seconds"])
        starts = BEAT_STARTS[chapter_index]
        inserts = []
        for position, start in enumerate(starts, start=1):
            next_start = starts[position] if position < len(starts) else duration
            asset_id = f"comic-frame-{chapter_index}-{position:02d}"
            file_path = relative_image_path(chapter_index, position)
            absolute_path = USER_DATA / file_path
            if not absolute_path.is_file():
                raise FileNotFoundError(absolute_path)
            material = {
                "id": asset_id,
                "type": "image",
                "name": f"Comic {chapter_index} · кадр {position:02d}",
                "size": absolute_path.stat().st_size,
                "mime": "image/png",
                "serverPath": file_path,
                "status": "ready",
                "role": "comic-frame",
                "chapterId": chapter_id,
                "beat": position,
            }
            materials.append(material)
            inserts.append({
                "id": f"comic-visual-{chapter_index}-{position:02d}",
                "assetId": asset_id,
                "type": "image",
                "enabled": True,
                "startTime": start,
                "duration": max(0.1, next_start - start),
                "transition": "crossfade",
                "transitionDuration": 0.75,
            })
            visual_rows.append((chapter_id, file_path, material["name"], {
                "role": "comic-frame",
                "chapterIndex": chapter_index,
                "beat": position,
                "startTime": start,
                "endTime": next_start,
                "assetId": asset_id,
            }))
        chapter["visualInserts"] = inserts

    settings["materials"] = materials
    plan = compile_scene_plan(settings)
    expected_frames = sum(len(starts) for starts in BEAT_STARTS.values())
    if plan["issues"] or len(plan["clips"]) != expected_frames:
        raise RuntimeError(f"Invalid comic scene plan: {plan}")

    now = utc_now()
    with repository.database.transaction() as connection:
        project_row = repository._project_row(connection, PROJECT_UUID)
        connection.execute(
            "DELETE FROM visual_assets WHERE project_id=? AND asset_type='comic-frame'",
            (project_row["id"],),
        )
        connection.executemany(
            """
            INSERT INTO visual_assets(
                project_id, chapter_id, file_path, asset_type, title,
                metadata_json, created_at
            ) VALUES (?, ?, ?, 'comic-frame', ?, ?, ?)
            """,
            [
                (project_row["id"], chapter_id, file_path, title,
                 json_text(metadata), now)
                for chapter_id, file_path, title, metadata in visual_rows
            ],
        )

    saved = repository.save_video_edition(
        PROJECT_UUID, settings, edition_id=EDITION_ID
    )
    report = {
        "projectUuid": PROJECT_UUID,
        "editionId": saved["id"],
        "chapters": len(CHAPTER_IDS),
        "frames": len(plan["clips"]),
        "globalPlaybackRate": GLOBAL_PLAYBACK_RATE,
        "chapter5CompactCharacters": len(book_chapters[128]["reader_content"]),
        "chapter5ArchivedTextVersionId": 4,
        "masterAudio": master["file_path"],
        "masterDuration": master["duration"],
        "issues": plan["issues"],
        "firstFrame": plan["clips"][0]["start"],
        "lastFrameEnd": plan["clips"][-1]["end"],
    }
    report_path = USER_DATA / "projects" / PROJECT_UUID / "comic-edition-report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report


if __name__ == "__main__":
    print(json.dumps(build(), ensure_ascii=False, indent=2))
