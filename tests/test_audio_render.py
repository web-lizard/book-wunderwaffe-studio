from __future__ import annotations

import array
import json
import math
import shutil
import subprocess
import tempfile
import unittest
import wave
import zipfile
from pathlib import Path
from unittest.mock import patch

import suviren_q_server as server
from bookender.audio_export import render_book_audio, validate_audio
from bookender.database import BookenderDatabase
from bookender.repository import ProjectRepository
from suviren_q import hidden_process_options, render_audio_master

ROOT = Path(__file__).resolve().parents[1]


def tone(path, frequency, duration=1):
    samples = array.array('h', (int(5000 * math.sin(2 * math.pi * frequency * i / 48000)) for i in range(round(48000 * duration))))
    with wave.open(str(path), 'wb') as output:
        output.setparams((1, 2, 48000, 0, 'NONE', 'not compressed'))
        output.writeframes(samples.tobytes())


def decode(path):
    data = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(path), '-f', 's16le', '-ac', '1', '-ar', '48000', '-'],
                          capture_output=True, check=True, **hidden_process_options()).stdout
    result = array.array('h'); result.frombytes(data)
    return result


def energy(samples, start, frequency, duration=.12):
    segment = samples[round(start * 48000):round((start + duration) * 48000)]
    return abs(sum(value * complex(math.cos(2 * math.pi * frequency * i / 48000),
                                   math.sin(2 * math.pi * frequency * i / 48000)) for i, value in enumerate(segment))) / max(1, len(segment))


@unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'FFmpeg and FFprobe required')
class AudioRenderTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.voice = self.root / 'voice.wav'; tone(self.voice, 330)
        self.other = self.root / 'other.wav'; tone(self.other, 550)
        self.music = self.root / 'music.wav'; tone(self.music, 880, 4)
        self.chapters = [
            dict(id=1, position=1, title='Начало', audio=self.voice, source_duration=1, start=0, duration=1),
            dict(id=3, position=3, title='Финал', audio=self.other, source_duration=1, start=2, duration=1),
        ]
        self.mix = dict(enabled=True, loop=True, volume=.5)
        self.clips = [dict(path=self.music, start=0, end=3, sourceOffset=0, volume=1)]

    def tearDown(self):
        self.temp.cleanup()

    def render(self, **kwargs):
        defaults = dict(chapters=self.chapters, export_dir=self.root / 'exports', title='Тест', mode='complete',
                        music_config=self.mix, music_clips=self.clips, music=None)
        defaults.update(kwargs)
        return render_book_audio(**defaults)

    def test_joined_mp3_has_correct_order_music_and_no_video_or_extra_gap(self):
        progress = []
        output = self.render(progress=lambda value, stage: progress.append(value))
        validate_audio(output, 2)
        samples = decode(output)
        self.assertAlmostEqual(len(samples) / 48000, 2, delta=.04)
        # MP3 spectral energy varies between FFmpeg/libmp3lame releases even
        # when the decoded tone, order and duration are correct.
        self.assertGreater(energy(samples, .4, 330), 1500)
        self.assertGreater(energy(samples, 1.4, 550), 1500)
        self.assertGreater(energy(samples, .4, 880), 700)
        self.assertGreater(energy(samples, 1.05, 880), 700)  # no new fade at chapter join
        self.assertEqual(progress, sorted(progress))
        self.assertFalse(list(output.parent.glob('.audio-export-*')))

    def test_chapter_zip_excludes_unselected_chapter_and_music_can_be_disabled(self):
        output = self.render(mode='chapters', music_config=dict(enabled=False))
        with zipfile.ZipFile(output) as archive:
            self.assertEqual([name for name in archive.namelist() if name.endswith('.mp3')], ['001 Начало.mp3', '003 Финал.mp3'])
            self.assertEqual([chapter['id'] for chapter in json.loads(archive.read('chapters.json'))], [1, 3])
            path = self.root / 'extracted.mp3'; path.write_bytes(archive.read('001 Начало.mp3'))
        self.assertLess(energy(decode(path), .4, 880), 20)

    def test_audio_seek_without_retiming_really_cuts_the_requested_window(self):
        source = self.root / 'two-tones.wav'
        with wave.open(str(self.voice), 'rb') as first, wave.open(str(self.other), 'rb') as second, wave.open(str(source), 'wb') as out:
            out.setparams(first.getparams()); out.writeframes(first.readframes(48000) + second.readframes(48000))
        output = self.root / 'cut.mp3'
        render_audio_master(source, output, start=1, duration=1)
        samples = decode(output)
        selected_tone = energy(samples, .3, 550)
        rejected_tone = energy(samples, .3, 330)
        self.assertGreater(selected_tone, 1500)
        self.assertLess(rejected_tone, selected_tone * .03)

    def test_retimed_chapter_and_short_looped_music(self):
        tone(self.music, 880, .25)
        chapter = dict(self.chapters[1], duration=1.5)
        output = self.render(chapters=[chapter], music_clips=[dict(self.clips[0], end=4)])
        validate_audio(output, 1.5)
        self.assertGreater(energy(decode(output), .5, 880), 700)

    def test_failed_render_does_not_publish_partial_output_or_leave_intermediates(self):
        with patch('bookender.audio_export.render_audio_master', side_effect=SystemExit('test failure')):
            with self.assertRaises(SystemExit):
                self.render()
        self.assertEqual(list((self.root / 'exports').iterdir()), [])


class BookAudioSelectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.patches = [patch('bookender.repository.USER_DATA', self.root), patch('suviren_q_server.USER_DATA', self.root)]
        for item in self.patches: item.start()
        self.repository = ProjectRepository(BookenderDatabase(self.root / 'book.db', ROOT / 'bookender/migrations'))
        self.repository.initialize()
        self.old_repository = server.PROJECT_REPOSITORY; server.PROJECT_REPOSITORY = self.repository
        self.project = self.repository.create_project(title='Тест', create_first_chapter=True)
        for index in range(2): self.repository.create_chapter(self.project['uuid'], title=f'Глава {index + 2}', content='Текст')
        self.project = self.repository.get_project(self.project['uuid'], include_details=True)
        self.ids = [chapter['id'] for chapter in self.project['chapters']]
        with self.repository.database.transaction() as connection:
            project_id = connection.execute('SELECT id FROM projects WHERE uuid=?', (self.project['uuid'],)).fetchone()['id']
            for index, chapter in enumerate(self.project['chapters']):
                relative = f"{self.project['project_folder']}/audio/{index}.wav"
                path = self.root / relative; path.parent.mkdir(parents=True, exist_ok=True); tone(path, 330)
                connection.execute("""INSERT INTO audio_assets(project_id, chapter_id, file_path, duration, generation_status,
                    voice, source_text_hash, created_at, updated_at, title, version_number, is_active, file_size)
                    VALUES (?, ?, ?, 1, 'ready', 'test', '', '2026-09-05', '2026-09-05', 'Голос', 1, 1, ?)""",
                    (project_id, chapter['id'], relative, path.stat().st_size))

    def tearDown(self):
        server.PROJECT_REPOSITORY = self.old_repository
        for item in reversed(self.patches): item.stop()
        self.temp.cleanup()

    def test_noncontiguous_selection_keeps_book_order_and_original_music_positions(self):
        request = server.BookAudioExportRequest(chapter_ids=[self.ids[2], self.ids[0]], editor_project={})
        result = server.prepare_book_audio_export(self.project['uuid'], request)
        self.assertEqual([chapter['id'] for chapter in result['chapters']], [self.ids[0], self.ids[2]])
        self.assertEqual([chapter['start'] for chapter in result['chapters']], [0, 2])

    def test_rejects_unknown_empty_and_unnarrated_selections_before_render(self):
        for ids in ([], [9999]):
            with self.assertRaises(ValueError):
                server.prepare_book_audio_export(self.project['uuid'], server.BookAudioExportRequest(chapter_ids=ids, editor_project={}))
        chapter = self.repository.create_chapter(self.project['uuid'], title='Без голоса', content='Текст')
        with self.assertRaisesRegex(ValueError, 'Нет готовой озвучки'):
            server.prepare_book_audio_export(self.project['uuid'], server.BookAudioExportRequest(chapter_ids=[chapter['id']], editor_project={}))
