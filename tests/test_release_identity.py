from __future__ import annotations

import json
import unittest
from pathlib import Path

from PIL import Image

import suviren_q
import suviren_q_server


ROOT = Path(__file__).resolve().parents[1]


class ReleaseIdentityTests(unittest.TestCase):
    def test_product_name_and_version_are_synchronized(self) -> None:
        self.assertEqual(suviren_q_server.APP_NAME, "Book Wunderwaffe Studio")
        self.assertEqual(suviren_q_server.APP_VERSION, "3.0.0")
        self.assertEqual(suviren_q.APP_VERSION, "3.0.0")
        self.assertEqual(
            suviren_q.APP_TITLE,
            "Book Wunderwaffe Studio 3.0.0",
        )

        package = json.loads(
            (ROOT / "ui" / "package.json").read_text(encoding="utf-8")
        )
        self.assertEqual(package["name"], "book-wunderwaffe-studio")
        self.assertEqual(package["version"], "3.0.0")

        interface = (ROOT / "ui" / "src" / "App.vue").read_text(encoding="utf-8")
        self.assertIn("BOOK WUNDERWAFFE STUDIO", interface)
        self.assertIn("backend.version || '3.0.0'", interface)

    def test_windows_icon_has_taskbar_sizes_and_native_binding(self) -> None:
        icon_path = ROOT / "assets" / "bookender-studio.ico"
        with Image.open(icon_path) as icon:
            self.assertTrue({(16, 16), (32, 32), (48, 48), (256, 256)}.issubset(icon.ico.sizes()))

        desktop = (ROOT / "book_wunderwaffe_desktop.py").read_text(encoding="utf-8")
        self.assertIn("SetCurrentProcessExplicitAppUserModelID", desktop)
        self.assertIn("apply_windows_taskbar_icon(self)", desktop)
        self.assertIn("wm_seticon = 0x0080", desktop)


if __name__ == "__main__":
    unittest.main()
