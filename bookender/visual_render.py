"""Content renderers and FFmpeg adapters for the canonical scene plan."""
from __future__ import annotations

from pathlib import Path
from typing import Callable


def draw_image_content(clip: dict, path: Path, size: tuple[int, int], font=None):
    from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageOps

    width, height = size
    layout = clip["layout"]
    with Image.open(path) as opened:
        source = ImageOps.exif_transpose(opened).convert("RGBA")
    # Flatten transparency against the same dark canvas used by the preview.
    flat = Image.new("RGBA", source.size, (12, 12, 18, 255))
    flat.alpha_composite(source)
    backdrop = ImageOps.fit(flat.convert("RGB"), size, method=Image.Resampling.LANCZOS)
    backdrop = backdrop.filter(ImageFilter.GaussianBlur(layout["blur"] * width / 1920))
    result = ImageEnhance.Brightness(backdrop).enhance(layout["brightness"]).convert("RGBA")
    rect = layout["image"]
    bounds = (max(1, round(width * rect["w"] / 100)), max(1, round(height * rect["h"] / 100)))
    image = ImageOps.contain(source, bounds, method=Image.Resampling.LANCZOS)
    result.alpha_composite(image, (round(width * rect["x"] / 100 + (bounds[0] - image.width) / 2),
                                   round(height * rect["y"] / 100 + (bounds[1] - image.height) / 2)))
    if clip.get("chapterTitle"):
        from suviren_q import _panel_font, _draw_panel_text_top

        rect = layout["badge"]
        label_font = _panel_font(font, max(9, round(width * 0.0125)))
        x, y = round(width * rect["x"] / 100), round(height * rect["y"] / 100)
        pad = max(3, round(width * 0.012))
        badge_height = max(12, round(height * rect["h"] / 100))
        badge = Image.new("RGBA", size)
        draw = ImageDraw.Draw(badge)
        text = str(clip["chapterTitle"])
        max_width = width * rect["w"] / 100 - pad * 2
        if draw.textlength(text, font=label_font) > max_width:
            while text and draw.textlength(text + "…", font=label_font) > max_width:
                text = text[:-1]
            text += "…"
        text_width = draw.textlength(text, font=label_font)
        draw.rounded_rectangle((x, y, x + text_width + pad * 2, y + badge_height), radius=5,
                               fill=(0, 0, 0, 148))
        box = draw.textbbox((0, 0), text, font=label_font)
        _draw_panel_text_top(draw, (x + pad, y + (badge_height - (box[3] - box[1])) / 2), text,
                             font=label_font, fill=(244, 240, 232, 255))
        result = Image.alpha_composite(result, badge)
    return result


CONTENT_RENDERERS: dict[str, Callable] = {"chapter_visual": draw_image_content}


def prepare_visual_content(plan: dict, project: dict, directory: Path, *,
                           width: int, height: int, editor_path: Path | None = None,
                           font: Path | None = None, render_end: float = float("inf")) -> list[dict]:
    materials = {str(asset.get("id")): asset for asset in project.get("materials", []) if isinstance(asset, dict)}
    rendered = []
    directory.mkdir(parents=True, exist_ok=True)
    for index, clip in enumerate(plan["clips"]):
        if clip["start"] >= render_end:
            continue
        material = materials.get(str(clip["assetId"]), {})
        value = material.get("serverPath")
        path = Path(str(value)) if value else None
        candidates = [path] if path else []
        if path and not path.is_absolute():
            candidates.extend([Path.cwd() / path, Path(__file__).resolve().parent.parent / path])
            if editor_path:
                candidates.extend([editor_path.parent / path, editor_path.parent.parent / path])
        source = next((candidate for candidate in candidates if candidate.is_file()), None)
        if source is None:
            raise ValueError(f"Illustration file is missing: {material.get('name', clip['assetId'])}")
        destination = directory / f"visual-{index:04d}.png"
        CONTENT_RENDERERS[clip["mode"]](clip, source, (width, height), font).save(destination, "PNG")
        rendered.append({**clip, "path": str(destination)})
    return rendered


def visual_filter_chain(clips: list[dict], *, start: float, duration: float,
                        input_offset: int, base_label: str) -> tuple[list[str], str]:
    """Translate the plan's opacity envelope into native FFmpeg alpha fades.

    Fade timestamps use book time, including for a render starting inside an
    insert. The video timestamps are restored before composition. Audio never
    enters this branch.
    """
    parts = []
    current = base_label
    for index, clip in enumerate(clips):
        label = f"visual_content_{index}"
        filters = ["format=rgba", f"setpts=PTS+{start:.9f}/TB"]
        frames = clip["opacity"]
        for left, right in zip(frames, frames[1:]):
            fade_duration = right[0] - left[0]
            if fade_duration <= 0:
                continue
            if left[1] == 0 and right[1] == 1:
                filters.append(
                    f"fade=t=in:st={left[0]:.9f}:d={fade_duration:.9f}:alpha=1"
                )
            elif left[1] == 1 and right[1] == 0:
                filters.append(
                    f"fade=t=out:st={left[0]:.9f}:d={fade_duration:.9f}:alpha=1"
                )
        filters.append(f"setpts=PTS-{start:.9f}/TB")
        parts.append(f"[{input_offset + index}:v]{','.join(filters)}[{label}]")
        next_label = f"visual_composite_{index}"
        begin, end = clip["start"] - start, clip["end"] - start
        parts.append(f"[{current}][{label}]overlay=0:0:format=auto:eof_action=pass:"
                     f"enable='gte(t,{begin:.9f})*lt(t,{end:.9f})'[{next_label}]")
        current = next_label
    return parts, current
