"""Parsing and composition for optional per-chapter technical canon blocks."""

from __future__ import annotations

import re
from dataclasses import dataclass


TECH_CHAPTER_START = "[[TECH_CHAPTER]]"
TECH_CHAPTER_END = "[[/TECH_CHAPTER]]"

_BLOCK_PATTERN = re.compile(
    r"(?ms)^[ \t]*\[\[TECH_CHAPTER\]\][ \t]*\r?\n?"
    r"(.*?)"
    r"^[ \t]*\[\[/TECH_CHAPTER\]\][ \t]*(?:\r?\n)?"
)
_WORD_PATTERN = re.compile(r"[^\W_]+(?:[-’'][^\W_]+)*", re.UNICODE)


class TechChapterSyntaxError(ValueError):
    """Raised when TECH_CHAPTER markers are incomplete or overlap."""


@dataclass(frozen=True)
class ChapterTextParts:
    author_text: str
    reader_text: str
    technical_content: str
    has_technical_block: bool


def split_chapter_text(value: str | None) -> ChapterTextParts:
    """Split stored author text into technical canon and reader-facing prose."""

    author_text = str(value or "")
    start_count = author_text.count(TECH_CHAPTER_START)
    end_count = author_text.count(TECH_CHAPTER_END)
    matches = list(_BLOCK_PATTERN.finditer(author_text))
    if start_count != end_count or len(matches) != start_count:
        raise TechChapterSyntaxError(
            "Служебный блок главы должен иметь парные маркеры "
            f"{TECH_CHAPTER_START} и {TECH_CHAPTER_END}."
        )

    technical_sections = [match.group(1).strip("\r\n") for match in matches]
    reader_text = _BLOCK_PATTERN.sub("", author_text).lstrip("\r\n")
    return ChapterTextParts(
        author_text=author_text,
        reader_text=reader_text,
        technical_content="\n\n".join(
            section for section in technical_sections if section.strip()
        ),
        has_technical_block=bool(matches),
    )


def reader_text(value: str | None) -> str:
    """Return prose safe for readers, TTS, captions and clean exports."""

    return split_chapter_text(value).reader_text


def compose_chapter_text(technical_content: str | None, prose: str | None) -> str:
    """Build canonical stored author text from optional TECH metadata and prose."""

    technical = str(technical_content or "").strip("\r\n")
    artistic = str(prose or "").lstrip("\r\n")
    if not technical.strip():
        return artistic
    return (
        f"{TECH_CHAPTER_START}\n{technical}\n{TECH_CHAPTER_END}"
        + (f"\n\n{artistic}" if artistic else "")
    )


def preserve_technical_block(
    existing: str | None,
    incoming: str | None,
    *,
    remove: bool = False,
) -> str:
    """Preserve existing TECH metadata unless replacement/removal is explicit."""

    current = split_chapter_text(existing)
    replacement = split_chapter_text(incoming)
    if remove:
        return replacement.reader_text
    if replacement.has_technical_block or not current.has_technical_block:
        return replacement.author_text
    return compose_chapter_text(current.technical_content, replacement.reader_text)


def chapter_text_metadata(value: str | None) -> dict[str, object]:
    """Return API-friendly derived fields for one stored chapter."""

    parts = split_chapter_text(value)
    prose = parts.reader_text
    paragraphs = [item for item in re.split(r"\r?\n\s*\r?\n", prose) if item.strip()]
    return {
        "reader_content": prose,
        "technical_content": parts.technical_content,
        "has_technical_block": parts.has_technical_block,
        "artistic_word_count": len(_WORD_PATTERN.findall(prose)),
        "artistic_character_count": len(prose),
        "artistic_paragraph_count": len(paragraphs),
    }
