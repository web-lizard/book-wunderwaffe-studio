from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from bookender.database import BookenderDatabase
from bookender.repository import ProjectRepository


ROOT = Path(__file__).resolve().parents[1]


class ProjectRepositoryTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.user_data = Path(self.temp.name) / "user_data"
        self.database = BookenderDatabase(
            self.user_data / "bookender.db", ROOT / "bookender" / "migrations"
        )
        self.user_patch = patch("bookender.repository.USER_DATA", self.user_data)
        self.backup_patch = patch(
            "bookender.repository.BACKUPS_DIR", self.user_data / "backups"
        )
        self.user_patch.start()
        self.backup_patch.start()
        self.repository = ProjectRepository(self.database)
        self.repository.initialize()

    def tearDown(self) -> None:
        self.backup_patch.stop()
        self.user_patch.stop()
        self.temp.cleanup()

    def test_projects_keep_books_settings_and_timelines_isolated(self) -> None:
        project_a = self.repository.create_project(
            title="Книга A", create_first_chapter=True, voice="ru-RU-SvetlanaNeural"
        )
        project_b = self.repository.create_project(
            title="Книга B", create_first_chapter=True, voice="ru-RU-DmitryNeural"
        )
        chapter_a = project_a["chapters"][0]
        chapter_b = project_b["chapters"][0]
        self.repository.update_chapter(
            project_a["uuid"], chapter_a["id"], content="Текст только A"
        )
        self.repository.update_chapter(
            project_b["uuid"], chapter_b["id"], content="Texte français — seulement B"
        )
        edition_a = self.repository.create_video_edition(project_a["uuid"])
        edition_b = self.repository.create_video_edition(project_b["uuid"])
        payload_a = edition_a["settings"]
        self.assertEqual(payload_a["chapters"][0]["text"], "Текст только A")
        self.assertEqual(
            payload_a["chapters"][0]["sourceContentHash"],
            self.repository.get_chapter(project_a["uuid"], chapter_a["id"])[
                "content_hash"
            ],
        )
        payload_a["scenes"] = [{"id": "a", "name": "A", "start": 0, "end": 10}]
        self.repository.save_video_edition(
            project_a["uuid"], payload_a, edition_id=edition_a["id"]
        )
        payload_b = edition_b["settings"]
        payload_b["scenes"] = [{"id": "b", "name": "B", "start": 20, "end": 30}]
        self.repository.save_video_edition(
            project_b["uuid"], payload_b, edition_id=edition_b["id"]
        )

        restored_a = self.repository.open_project(project_a["uuid"])
        restored_b = self.repository.open_project(project_b["uuid"])
        self.assertEqual(restored_a["chapters"][0]["content"], "Текст только A")
        self.assertIn("français", restored_b["chapters"][0]["content"])
        self.assertEqual(
            restored_a["video_editions"][0]["settings"]["scenes"][0]["id"], "a"
        )
        self.assertEqual(
            restored_b["video_editions"][0]["settings"]["scenes"][0]["id"], "b"
        )
        self.assertEqual(
            self.repository.active_project_uuid(), project_b["uuid"]
        )

    def test_reorder_archive_duplicate_and_backup(self) -> None:
        project = self.repository.create_project(title="Порядок")
        chapters = [
            self.repository.create_chapter(project["uuid"], title=f"Глава {index}")
            for index in range(3)
        ]
        order = [chapters[2]["id"], chapters[0]["id"], chapters[1]["id"]]
        self.repository.reorder_chapters(project["uuid"], order)
        self.repository.archive_chapter(project["uuid"], chapters[0]["id"])
        restored = self.repository.get_project(project["uuid"], include_details=True)
        self.assertEqual(
            [item["id"] for item in restored["chapters"]],
            [chapters[2]["id"], chapters[1]["id"]],
        )
        duplicate = self.repository.duplicate_project(project["uuid"])
        self.assertEqual(len(duplicate["chapters"]), 2)
        backup = self.repository.backup_project(project["uuid"])
        self.assertTrue((backup / "project.json").is_file())
        self.assertTrue((backup / "manifest.json").is_file())

    def test_duplicate_copies_media_files_assets_and_remaps_chapters(self) -> None:
        project = self.repository.create_project(
            title="Оригинал", create_first_chapter=True
        )
        chapter = project["chapters"][0]
        source_relative = (
            Path(project["project_folder"]) / "audio" / "chapter.mp3"
        ).as_posix()
        source_file = self.user_data / source_relative
        source_file.write_bytes(b"narration")
        with self.database.transaction() as connection:
            connection.execute(
                """
                INSERT INTO audio_assets(
                    project_id, chapter_id, file_path, duration,
                    generation_status, voice, source_text_hash,
                    created_at, updated_at, title, is_active, file_size
                )
                SELECT id, ?, ?, 12.5, 'ready', 'voice', '',
                       created_at, updated_at, 'Озвучка', 1, ?
                FROM projects WHERE uuid=?
                """,
                (chapter["id"], source_relative, source_file.stat().st_size, project["uuid"]),
            )
        edition = self.repository.create_video_edition(project["uuid"])
        payload = edition["settings"]
        payload["materials"] = [{
            "id": "voice", "type": "audio", "serverPath": source_relative,
            "chapterId": chapter["id"],
        }]
        self.repository.save_video_edition(
            project["uuid"], payload, edition_id=edition["id"]
        )

        duplicate = self.repository.duplicate_project(
            project["uuid"], title="Оригинал — сохранённая версия"
        )
        copied_chapter = duplicate["chapters"][0]
        copied_audio = duplicate["audio_assets"][0]
        copied_settings = duplicate["video_editions"][0]["settings"]
        self.assertEqual(duplicate["title"], "Оригинал — сохранённая версия")
        self.assertNotEqual(copied_chapter["id"], chapter["id"])
        self.assertEqual(copied_audio["chapter_id"], copied_chapter["id"])
        self.assertIn(duplicate["uuid"], copied_audio["file_path"])
        self.assertNotIn(project["uuid"], copied_audio["file_path"])
        self.assertEqual(
            copied_settings["chapters"][0]["chapterId"], copied_chapter["id"]
        )
        self.assertEqual(
            copied_settings["materials"][0]["chapterId"], copied_chapter["id"]
        )
        self.assertTrue((self.user_data / copied_audio["file_path"]).is_file())

    def test_chapter_zero_is_unique_and_inserted_before_regular_chapters(self) -> None:
        project = self.repository.create_project(
            title="Книга с прологом", create_first_chapter=True
        )
        second = self.repository.create_chapter(project["uuid"], title="Глава 2")
        zero = self.repository.create_chapter(
            project["uuid"], title="Глава 0", is_zero=True
        )
        restored = self.repository.get_project(project["uuid"], include_details=True)
        self.assertEqual([item["position"] for item in restored["chapters"]], [0, 1, 2])
        self.assertEqual(restored["chapters"][0]["id"], zero["id"])
        self.assertTrue(restored["chapters"][0]["is_zero"])
        self.assertEqual(restored["chapters"][2]["id"], second["id"])
        with self.assertRaises(ValueError):
            self.repository.create_chapter(project["uuid"], is_zero=True)

    def test_chapter_visual_is_project_owned_and_removable(self) -> None:
        project = self.repository.create_project(
            title="Книга с иллюстрацией", create_first_chapter=True
        )
        chapter = project["chapters"][0]
        relative_path = (
            Path(project["project_folder"]) / "images" / "chapter.jpg"
        ).as_posix()
        image_path = self.user_data / relative_path
        image_path.write_bytes(b"test-image")
        asset = self.repository.add_chapter_visual_asset(
            project["uuid"],
            chapter["id"],
            file_path=relative_path,
            title="Иллюстрация",
            metadata={"size": image_path.stat().st_size},
        )
        restored = self.repository.get_project(
            project["uuid"], include_details=True
        )
        self.assertEqual(restored["visual_assets"][0]["id"], asset["id"])
        self.assertEqual(restored["visual_assets"][0]["chapter_id"], chapter["id"])
        removed = self.repository.remove_chapter_visual_asset(
            project["uuid"], chapter["id"]
        )
        self.assertTrue(removed["deleted"])
        self.assertFalse(image_path.exists())


if __name__ == "__main__":
    unittest.main()
