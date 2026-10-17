from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import suviren_q_server as server
from bookender.chapter_tech import (
    TechChapterSyntaxError,
    chapter_text_metadata,
    compose_chapter_text,
    split_chapter_text,
)
from bookender.database import BookenderDatabase
from bookender.repository import ProjectRepository
from bookender.tts import TtsService


ROOT = Path(__file__).resolve().parents[1]


class ChapterTechParserTests(unittest.TestCase):
    def test_split_keeps_author_text_but_removes_tech_from_reader_text_and_counts(self) -> None:
        content = compose_chapter_text(
            "chapter: Тест\n\n[CHARACTERS]\nМуса: чёрный пиджак.",
            "Глава 1. Тест\n\nДва художественных слова.",
        )
        parts = split_chapter_text(content)
        metadata = chapter_text_metadata(content)

        self.assertTrue(parts.has_technical_block)
        self.assertIn("Муса", parts.technical_content)
        self.assertNotIn("TECH_CHAPTER", parts.reader_text)
        self.assertNotIn("чёрный пиджак", parts.reader_text)
        self.assertEqual(metadata["artistic_word_count"], 6)
        self.assertEqual(metadata["artistic_paragraph_count"], 2)

    def test_unpaired_markers_are_rejected(self) -> None:
        with self.assertRaises(TechChapterSyntaxError):
            split_chapter_text("[[TECH_CHAPTER]]\n[VISUAL]\nБез конца")


class ChapterTechIntegrationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.user_data = Path(self.temp.name) / "user_data"
        self.patches = [
            patch("bookender.repository.USER_DATA", self.user_data),
            patch("bookender.tts.USER_DATA", self.user_data),
            patch("suviren_q_server.USER_DATA", self.user_data),
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
        self.old_repository = server.PROJECT_REPOSITORY
        server.PROJECT_REPOSITORY = self.repository
        self.project = self.repository.create_project(
            title="TECH test", create_first_chapter=True
        )
        self.chapter_id = self.project["chapters"][0]["id"]

    def tearDown(self) -> None:
        server.PROJECT_REPOSITORY = self.old_repository
        for item in reversed(self.patches):
            item.stop()
        self.temp.cleanup()

    def test_tech_survives_prose_rewrite_and_can_be_removed_explicitly(self) -> None:
        stored = compose_chapter_text("[VISUAL]\nФиолетовый свет.", "Старый текст.")
        saved = self.repository.update_chapter(
            self.project["uuid"], self.chapter_id, content=stored
        )
        narration_hash = saved["narration_hash"]

        rewritten = self.repository.update_chapter(
            self.project["uuid"], self.chapter_id, content="Новый текст."
        )
        self.assertTrue(rewritten["has_technical_block"])
        self.assertIn("Фиолетовый свет", rewritten["technical_content"])
        self.assertEqual(rewritten["reader_content"], "Новый текст.")
        self.assertNotEqual(rewritten["narration_hash"], narration_hash)

        removed = self.repository.update_chapter(
            self.project["uuid"],
            self.chapter_id,
            content="Новый текст.",
            remove_technical_block=True,
        )
        self.assertFalse(removed["has_technical_block"])
        self.assertNotIn("TECH_CHAPTER", removed["content"])

    def test_clean_export_and_tts_job_exclude_technical_block(self) -> None:
        stored = compose_chapter_text(
            "[ILLUSTRATION_RULES]\nСекретный визуальный канон.",
            "Только художественный текст.",
        )
        chapter = self.repository.update_chapter(
            self.project["uuid"], self.chapter_id, content=stored
        )

        exported = server.chapter_export_text(chapter, 1)
        self.assertIn("Только художественный текст", exported)
        self.assertNotIn("Секретный визуальный канон", exported)
        self.assertNotIn("TECH_CHAPTER", exported)

        service = TtsService(self.repository)
        with patch.object(service, "require_available", return_value=None):
            job = service.queue_chapter(
                self.project["uuid"], self.chapter_id, start=False
            )
        self.assertEqual(job["source_text_hash"], chapter["narration_hash"])
        self.assertEqual(job["progress_total"], 1)


if __name__ == "__main__":
    unittest.main()
