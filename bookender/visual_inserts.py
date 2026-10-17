"""Canonical visual schedule shared by the editor API and the video exporter.

Stored times are chapter-relative seconds. The compiled plan uses absolute,
half-open intervals and opacity keyframes; consumers only interpolate them.
Adding a content renderer does not change the timeline or persistent layers.
"""
from __future__ import annotations

import math
from typing import Any


IMAGE_LAYOUT = {
    "image": {"x": 3, "y": 3, "w": 94, "h": 86},
    "badge": {"x": 4, "y": 4, "w": 76, "h": 5},
    "blur": 24,
    "brightness": 0.38,
}
CONTENT_MODES = {"image": "chapter_visual", "chapter_visual": "chapter_visual"}


def number(value: Any, default: float = 0.0) -> float:
    try:
        result = float(value)
        return result if math.isfinite(result) else default
    except (TypeError, ValueError, OverflowError):
        return default


def normalize_visual_inserts(value: Any, chapter_id: Any = "chapter") -> list[dict]:
    if not isinstance(value, list):
        return []
    result = []
    used_ids = set()
    for index, raw in enumerate(value):
        if not isinstance(raw, dict) or raw.get("assetId") is None:
            continue
        clip_id = str(raw.get("id") or f"visual-{chapter_id}-{index}")
        if clip_id in used_ids:
            clip_id = f"{clip_id}-{index}"
        used_ids.add(clip_id)
        result.append({
            **raw,
            "id": clip_id,
            "type": str(raw.get("type") or "image"),
            "startTime": max(0.0, number(raw.get("startTime"))),
            "duration": max(0.0, number(raw.get("duration"))),
            "transition": "none" if raw.get("transition") == "none" else "crossfade",
            "transitionDuration": min(1.0, max(0.5, number(raw.get("transitionDuration"), 0.75))),
        })
    return result


def chapter_bounds(project: dict) -> list[tuple[dict, float, float]]:
    chapters = sorted(
        (ch for ch in project.get("chapters", []) if isinstance(ch, dict)),
        key=lambda ch: number(ch.get("start_seconds")),
    )
    result = []
    for index, chapter in enumerate(chapters):
        start = max(0.0, number(chapter.get("start_seconds")))
        end = (number(chapters[index + 1].get("start_seconds")) if index + 1 < len(chapters)
               else number(chapter.get("end_seconds"), number(project.get("duration_seconds"))))
        if number(project.get("duration_seconds")) > 0:
            end = min(end, number(project["duration_seconds"]))
        result.append((chapter, start, max(start, end)))
    return result


def compile_scene_plan(project: dict) -> dict:
    """Resolve source overlaps and compile seamless slide-to-slide transitions.

    A later overlapping clip starts when the earlier one ends, retaining its
    original scheduled end. Adjacent visuals crossfade by keeping the outgoing
    slide fully opaque underneath while the incoming slide fades over it. This
    prevents the normal scene from flashing between illustrations. Source data
    is not changed so shortening/re-expanding a chapter doesn't destroy inserts.
    """
    assets = {str(a.get("id")): a for a in project.get("materials", []) if isinstance(a, dict)}
    clips, issues = [], []
    for chapter, chapter_start, chapter_end in chapter_bounds(project):
        chapter_id = chapter.get("id", chapter.get("chapterId"))
        occupied_until = chapter_start
        inserts = normalize_visual_inserts(chapter.get("visualInserts"), chapter_id)
        for insert in sorted(inserts, key=lambda item: item["startTime"]):
            if insert.get("enabled") is False:
                continue
            mode = CONTENT_MODES.get(insert["type"])
            asset = assets.get(str(insert["assetId"]))
            reason = ("unsupported-type" if not mode else
                      "missing-image" if not asset or asset.get("type") != "image" else None)
            if reason:
                issues.append({"id": insert["id"], "chapterId": chapter_id, "reason": reason})
                continue
            requested_start = chapter_start + insert["startTime"]
            start = max(requested_start, occupied_until)
            end = min(chapter_end, requested_start + insert["duration"])
            if end <= start:
                issues.append({"id": insert["id"], "chapterId": chapter_id, "reason": "outside-or-overlapped"})
                continue
            clips.append({
                **insert, "chapterId": chapter_id, "chapterTitle": chapter.get("title", ""),
                "mode": mode, "start": start, "end": end, "scheduledEnd": end,
                "opacity": [[start, 1], [end, 1]],
                "layout": IMAGE_LAYOUT,
            })
            occupied_until = end

    epsilon = 1e-6
    incoming_fades = [
        (min(clip["transitionDuration"], (clip["scheduledEnd"] - clip["start"]) / 2)
         if clip["transition"] != "none" else 0.0)
        for clip in clips
    ]
    for index, clip in enumerate(clips):
        scheduled_end = clip["scheduledEnd"]
        fade_in = incoming_fades[index]
        connected_next = (
            index + 1 < len(clips)
            and abs(clips[index + 1]["start"] - scheduled_end) <= epsilon
        )
        next_fade = incoming_fades[index + 1] if connected_next else 0.0
        frames = (
            [[clip["start"], 0], [clip["start"] + fade_in, 1]]
            if fade_in > 0
            else [[clip["start"], 1]]
        )
        if connected_next:
            # The outgoing slide stays opaque under the incoming alpha ramp.
            # Sequential alpha overlays then produce a true A→B crossfade,
            # without exposing the base composition between the two images.
            clip["end"] = scheduled_end + next_fade
            frames.extend([[scheduled_end, 1], [clip["end"], 1]])
        elif clip["transition"] != "none":
            fade_out = min(clip["transitionDuration"], (scheduled_end - clip["start"]) / 2)
            frames.extend([[scheduled_end - fade_out, 1], [scheduled_end, 0]])
        else:
            frames.append([scheduled_end, 1])
        clip["opacity"] = frames
    return {"version": 2, "clips": clips, "issues": issues}


def opacity_at(clip: dict, time: float) -> float:
    if not clip["start"] <= time < clip["end"]:
        return 0.0
    frames = clip["opacity"]
    for (left, a), (right, b) in zip(frames, frames[1:]):
        if time < right:
            return a + (b - a) * (time - left) / max(1e-9, right - left)
    return float(frames[-1][1])


def scene_at(plan: dict, time: float) -> dict:
    active = [
        {"visual": clip, "opacity": opacity_at(clip, time)}
        for clip in plan["clips"]
        if clip["start"] <= time < clip["end"]
    ]
    visual = active[-1]["visual"] if active else None
    return {
        "mode": visual["mode"] if visual else "normal",
        "visual": visual,
        "layers": active,
        "opacity": max((layer["opacity"] for layer in active), default=0.0),
    }


def auto_place_visuals(chapter: dict, duration: float, *, asset_ids: list | None = None,
                       append: bool = False) -> list[dict]:
    """Suggest spaced clips. Appending never moves existing manual edits."""
    existing = normalize_visual_inserts(chapter.get("visualInserts"), chapter.get("id"))
    if asset_ids is None:
        originals = existing or ([{"assetId": chapter["imageAssetId"]}] if chapter.get("imageAssetId") else [])
    else:
        originals = [{"assetId": asset_id} for asset_id in asset_ids]
    if not originals or duration <= 0:
        return existing if append else []
    result = list(existing) if append else []
    count = len(originals)
    if append:
        free_start = max((item["startTime"] + item["duration"] for item in existing), default=0)
        gap = min(30.0, duration * 0.1)
        free_start = free_start + gap if existing else 0
        available = max(0, duration - free_start)
    else:
        free_start, available = 0.0, duration
    slot = available / count
    if slot < 0.1:
        return result
    for index, original in enumerate(originals):
        lead = min(30.0, slot * 0.15)
        tail = min(30.0, slot * 0.15)
        length = min(180.0, slot - lead - tail)
        result.append({**original, "startTime": free_start + index * slot + lead,
                       "duration": length, "transition": original.get("transition", "crossfade"),
                       "transitionDuration": original.get("transitionDuration", 0.75)})
    return normalize_visual_inserts(result, chapter.get("id"))
