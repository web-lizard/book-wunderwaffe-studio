"""Assemble the first four chapters of the Sun Merchant Comic Edition.

The operation is deliberately scoped to the dedicated duplicate project. It is
idempotent: re-running it replaces only generated comic-frame records and the
edition's chapter 0-3 visual schedules.
"""

from __future__ import annotations

import json
from pathlib import Path

from bookender.paths import USER_DATA
from bookender.repository import ProjectRepository, json_text, utc_now
from bookender.visual_inserts import compile_scene_plan


PROJECT_UUID = "d1e0570e-03d8-4163-8312-7a6b41e2ddaa"
EDITION_ID = 7
CHAPTER_IDS = (123, 124, 125, 126)
BEAT_STARTS = {
    0: (0.0, 32.0, 64.0, 97.0, 130.0, 164.0),
    1: (0.0, 105.0, 210.0, 310.0, 425.0, 520.0, 625.0, 735.0),
    2: (0.0, 140.0, 275.0, 400.0, 525.0, 660.0, 810.0, 955.0, 1110.0),
    3: (0.0, 165.0, 325.0, 480.0, 645.0, 820.0, 985.0, 1150.0, 1325.0, 1490.0),
}


def relative_image_path(chapter_index: int, frame_index: int) -> str:
    return (
        f"projects/{PROJECT_UUID}/images/comic/"
        f"chapter-{chapter_index}-{frame_index:02d}.png"
    )


def build() -> dict:
    repository = ProjectRepository()
    repository.initialize()
    project = repository.get_project(PROJECT_UUID, include_details=True)
    if tuple(chapter["id"] for chapter in project["chapters"][:4]) != CHAPTER_IDS:
        raise RuntimeError("Comic Edition chapter mapping changed; refusing to edit another project")

    edition = repository.get_video_edition(PROJECT_UUID, EDITION_ID)
    settings = edition["settings"]
    chapters_by_database_id = {
        int(chapter.get("chapterId")): chapter for chapter in settings["chapters"]
    }

    settings["presentationMode"] = "comic"
    settings["glitch"] = False
    settings["backgroundBlur"] = False
    settings["captions"] = {"enabled": True, "wordsPerCard": 16}
    settings["comic"] = {
        "version": 1,
        "chapterTitleSeconds": 8,
        "controlsAutoHideMs": 2200,
        "kenBurns": True,
        "scope": "chapters-0-3",
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
    if plan["issues"] or len(plan["clips"]) != 33:
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
        "issues": plan["issues"],
        "firstFrame": plan["clips"][0]["start"],
        "lastFrameEnd": plan["clips"][-1]["end"],
    }
    report_path = USER_DATA / "projects" / PROJECT_UUID / "comic-edition-report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report


if __name__ == "__main__":
    print(json.dumps(build(), ensure_ascii=False, indent=2))
