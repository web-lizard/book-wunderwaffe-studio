import assert from 'node:assert/strict'
import test from 'node:test'

import {
  MAX_CHAPTER_PLAYBACK_RATE,
  MIN_CHAPTER_PLAYBACK_RATE,
  VISUALIZER_COLOR_PRESETS,
  chapterPlaybackRate,
  chapterTimelineWordTimings,
  formatTime,
  freshProject,
  materialType,
  normalizeChapter,
  normalizeProject,
  parseTimecode,
  timedTextWords,
  wordAtTime,
} from '../src/editorModel.js'

test('fresh projects do not share nested mutable defaults', () => {
  const first = freshProject()
  const second = freshProject()
  first.layers.caption.fontSize = 99
  first.music.volume = 1
  first.scenes[0].name = 'Изменено'
  assert.equal(second.layers.caption.fontSize, 28)
  assert.equal(second.music.volume, 0.16)
  assert.equal(second.scenes[0].name, 'Основная сцена')
})

test('project normalization repairs legacy and malformed editor data', () => {
  const normalized = normalizeProject({
    renderPreset: 'missing',
    musicAssetId: 'music',
    duration_seconds: 90,
    backgroundBlur: false,
    materials: [{ id: 'music', type: 'audio', serverPath: 'projects/demo/music.mp3' }],
    chapters: [{ start: '00:10', end: '00:05', playbackRate: 99, visualInserts: [
      { assetId: 'art', startTime: -4, duration: -2, transitionDuration: 9 },
    ] }],
    layers: { visualizer: { colorPreset: 'unknown' } },
  }, { mediaUrl: path => `/media/${path}` })

  assert.equal(normalized.renderPreset, 'balanced')
  assert.equal(normalized.backgroundBlur, false)
  assert.equal(normalized.materials[0].src, '/media/projects/demo/music.mp3')
  assert.equal(normalized.musicClips.length, 1)
  assert.equal(normalized.musicClips[0].end, 90)
  assert.equal(normalized.chapters[0].start_seconds, 10)
  assert.equal(normalized.chapters[0].end_seconds, 10)
  assert.equal(normalized.chapters[0].playbackRate, MAX_CHAPTER_PLAYBACK_RATE)
  assert.equal(normalized.chapters[0].visualInserts[0].startTime, 0)
  assert.equal(normalized.chapters[0].visualInserts[0].duration, 0)
  assert.equal(normalized.chapters[0].visualInserts[0].transitionDuration, 1)
  assert.equal(normalized.layers.visualizer.colorPreset, VISUALIZER_COLOR_PRESETS[0].id)
})

test('chapter timing clamps playback rates and scales exact word boundaries', () => {
  const fast = normalizeChapter({ start_seconds: 5, end_seconds: 5.1, sourceStartSeconds: 10, sourceEndSeconds: 20 }, 0)
  assert.equal(chapterPlaybackRate(fast), MAX_CHAPTER_PLAYBACK_RATE)
  const slow = normalizeChapter({ start_seconds: 0, end_seconds: 100, sourceStartSeconds: 0, sourceEndSeconds: 1 }, 0)
  assert.equal(chapterPlaybackRate(slow), MIN_CHAPTER_PLAYBACK_RATE)

  const chapter = {
    start_seconds: 20,
    end_seconds: 25,
    sourceStartSeconds: 10,
    sourceEndSeconds: 20,
    wordTimings: [{ text: 'слово', start: 12, end: 14 }],
  }
  assert.deepEqual(chapterTimelineWordTimings(chapter), [{ text: 'слово', start: 21, end: 22 }])

  const malformed = normalizeChapter({
    start_seconds: 3,
    end_seconds: 8,
    sourceStartSeconds: 'not-a-number',
    sourceEndSeconds: 9,
  }, 0)
  assert.equal(malformed.sourceStartSeconds, 3)
  assert.equal(malformed.sourceEndSeconds, 9)
})

test('word timing merges exact tokenizer fragments and binary lookup follows playback', () => {
  const words = timedTextWords('Санкт-Петербург снова', 10, 14, [
    { text: 'Санкт', start: 10.5, end: 10.9 },
    { text: 'Петербург', start: 10.9, end: 11.4 },
    { text: 'снова', start: 12, end: 12.4 },
  ])
  assert.equal(words[0].timing, 'exact')
  assert.equal(words[0].start, 10.5)
  assert.equal(words[0].end, 11.4)
  assert.equal(wordAtTime(words, 12.1).text, 'снова')
})

test('time and material helpers accept editor-facing formats', () => {
  assert.equal(parseTimecode('01:02:03,5'), 3723.5)
  assert.equal(formatTime(3723.9), '01:02:03')
  assert.equal(materialType('voice.MP3'), 'audio')
  assert.equal(materialType('frame.bin', 'image/webp'), 'image')
  assert.equal(materialType('notes.txt'), null)
})
