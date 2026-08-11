import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')

test('book audio can be played, dragged, and assembled for video', () => {
  assert.match(source, /<audio[^>]+controls/)
  assert.match(source, /application\/x-bookender-audio-id/)
  assert.match(source, /dropBookAudioOnVideoTab/)
  assert.match(source, /\/video-audio\/master/)
})

test('a narrated chapter can be downloaded as an audio-only mixed MP3', () => {
  assert.match(source, /⇩ Скачать аудио/)
  assert.match(source, /Собираем аудио…/)
  assert.match(source, /downloadCurrentChapterAudio/)
  assert.match(source, /\/audio-export/)
  assert.match(source, /editor_project: serializeProject\(\)/)
  assert.match(source, /\.mp3`/)
})

test('chapter images and optional reading captions reach the video workspace', () => {
  assert.match(source, /\/chapters\/\$\{chapterId\}\/image/)
  assert.match(source, /imageAssetId/)
  assert.match(source, /project\.captions\.enabled/)
  assert.match(source, /currentReadingCaption/)
})

test('reading captions are a movable layer with adjustable typography', () => {
  assert.match(source, /onLayerPointerDown\('caption'/)
  assert.match(source, /caption:\s*\{[^}]+fontWeight:\s*400/)
  assert.match(source, /selectedLayer\.fontWeight/)
  assert.match(source, /--caption-weight/)
  assert.match(source, /source\.layers\?\.caption/)
})

test('video projects support a separate music bed with volume and three-band EQ', () => {
  assert.match(source, /musicAssetId/)
  assert.match(source, /MUSIC_EQ_BANDS/)
  assert.match(source, /project\.music\.volume/)
  assert.match(source, /project\.music\[band\.key\]/)
  assert.match(source, /class="timeline-lane music-lane"/)
  assert.match(source, /ensureMusicGraph/)
  assert.match(source, /createBiquadFilter/)
})

test('music volume is directly reachable from the timeline and has quick presets', () => {
  assert.match(source, /class="music-mixer-shortcut"/)
  assert.match(source, /class="timeline-track-button"/)
  assert.match(source, /selection\.type === 'music'/)
  assert.match(source, /setMusicVolume\(0\.08\)/)
  assert.match(source, /setMusicVolume\(0\.16\)/)
  assert.match(source, /setMusicVolume\(0\.3\)/)
  assert.match(source, /Громкость музыки/)
})

test('music preview applies native volume and reports whether live EQ is active', () => {
  assert.match(source, /element\.volume = level/)
  assert.match(source, /setAudioValue\(musicGain\?\.gain, 1\)/)
  assert.match(source, /parameter\.cancelScheduledValues\(now\)/)
  assert.match(source, /async function onMusicControlInput/)
  assert.match(source, /async function toggleMusicPreview/)
  assert.match(source, /musicEqState\.active/)
  assert.match(source, /EQ активен/)
})

test('opening video rebuilds narrated chapters and exposes chapter audio clips', () => {
  assert.match(source, /workspace === 'video' && activeProjectRecord\.value\?\.book/)
  assert.match(source, /await openBookInVideo\(\)/)
  assert.match(source, /await rebuildBookVideo\(activeProjectRecord\.value/)
  assert.match(source, /audioAssetId: audioMaterials\.get\(chapter\.id\)/)
  assert.match(source, /v-for="chapter in videoAudioChapters"/)
  assert.match(source, /filter\(\(chapter\) => timings\.has\(chapter\.id\)\)/)
  assert.match(source, /ensureDefaultVideoCover/)
  assert.match(source, />Текст на экране</)
})

test('book export is visible and supports whole, current, and chapter bundle modes', () => {
  assert.match(source, />Экспорт книги</)
  assert.match(source, /downloadBookExport\('complete'\)/)
  assert.match(source, /downloadBookExport\('chapter'\)/)
  assert.match(source, /downloadBookExport\('chapters'\)/)
  assert.match(source, /bookExportIncludeMedia/)
})

test('book toolbar is compact and narration exposes real progress', () => {
  assert.match(source, /class="book-icon-action"/)
  assert.match(source, /aria-label="Новая книга"/)
  assert.match(source, /aria-label="Резервная копия"/)
  assert.match(source, /ttsProgressState\.percent/)
  assert.match(source, /progress_total/)
  assert.match(source, /Прошло/)
})

test('book chapters support chapter zero, drag reorder, and race-safe text synchronization', () => {
  assert.match(source, /createBookZeroChapter/)
  assert.match(source, /chapterDisplayNumber/)
  assert.match(source, /startBookChapterDrag/)
  assert.match(source, /dropBookChapter/)
  assert.match(source, /persistBookChapterOrder/)
  assert.match(source, /bookEditRevision/)
  assert.match(source, /bookSavePromise/)
  assert.match(source, /syncSavedBookChapterToVideo/)
  assert.match(source, /sourceContentHash/)
  assert.match(source, /invalidAudioIds/)
  assert.match(source, /material\.role === 'book-master'/)
  assert.match(source, /installBookRecord\(record, \{ preserveDraft: !draftSaved \}\)/)
  assert.match(source, /bookVideoTextSyncState\.value === 'error'/)
  assert.match(source, /chapterTimelineWords\(currentChapter\.value\)/)
})

test('video supports background-only mode, sharp backdrops, layer resizing, and twelve visualizer palettes', () => {
  assert.match(source, /coverMode:\s*'cover'/)
  assert.match(source, /backgroundBlur:\s*true/)
  assert.match(source, /setCoverEnabled/)
  assert.match(source, /background-unblurred/)
  assert.match(source, /onLayerResizePointerDown/)
  assert.match(source, /resize-e/)
  assert.match(source, /resize-s/)
  assert.match(source, /resize-se/)
  const presets = source.slice(
    source.indexOf('const VISUALIZER_COLOR_PRESETS'),
    source.indexOf('const DEFAULT_LAYERS'),
  )
  assert.equal((presets.match(/\{ id:/g) || []).length, 12)
  assert.match(source, /setVisualizerColorPreset/)
})

test('timeline playhead supports pointer scrubbing and keyboard seeking', () => {
  assert.match(source, /ref="timelineContent"/)
  assert.match(source, /@pointerdown\.stop="startTimelineScrub"/)
  assert.match(source, /window\.addEventListener\('pointermove', timelinePointerMove\)/)
  assert.match(source, /window\.addEventListener\('pointerup', timelinePointerUp\)/)
  assert.match(source, /function onTimelinePlayheadKeydown/)
  assert.match(source, /event\.key === 'ArrowLeft'/)
  assert.match(source, /event\.key === 'ArrowRight'/)
})

test('timeline music items support Reaper-style dragging, trimming, splitting, and navigation', () => {
  assert.match(source, /musicClips/)
  assert.match(source, /application\/x-suviren-audio-asset-id/)
  assert.match(source, /dropMusicOnTimeline/)
  assert.match(source, /startMusicClipPointer/)
  assert.match(source, /trim-start/)
  assert.match(source, /splitSelectedMusicClip/)
  assert.match(source, /event\.key\.toLowerCase\(\) === 's'/)
  assert.match(source, /onTimelineWheel/)
  assert.match(source, /startTimelinePan/)
  assert.match(source, /startTimelineResize/)
})

test('timeline magnet snaps book elements to chapters, clips, and the adaptive grid', () => {
  assert.match(source, /class="magnet-toggle"/)
  assert.match(source, /function toggleTimelineSnap/)
  assert.match(source, /function timelineSnapCandidates/)
  assert.match(source, /timelineGridStep/)
  assert.match(source, /class="timeline-snap-guide"/)
  assert.match(source, /chapterBound/)
  assert.match(source, /syncChapterBoundMusicClips/)
  assert.match(source, /startChapterBoundaryPointer/)
  assert.match(source, /startSceneClipPointer/)
})

test('chapter items support ripple time stretching with rate-aware playback', () => {
  assert.match(source, /class="chapter-stretch-handle"/)
  assert.match(source, /startChapterStretchPointer/)
  assert.match(source, /function retimeChapterToDuration/)
  assert.match(source, /sourceStartSeconds/)
  assert.match(source, /sourceEndSeconds/)
  assert.match(source, /function sourceTimeAtTimeline/)
  assert.match(source, /function timelineTimeAtSource/)
  assert.match(source, /master\.playbackRate = rate/)
  assert.match(source, /setChapterDuration\(selectedChapter/)
})

test('text navigator maps words to the timeline and plays selected excerpts', () => {
  assert.match(source, /Текст книги/)
  assert.match(source, /id="text-navigator-title"/)
  assert.match(source, /function chapterTimelineWords/)
  assert.match(source, /event\.shiftKey/)
  assert.match(source, /seekTo\(word\.start\)/)
  assert.match(source, /scrollTimelineToTime\(word\.start\)/)
  assert.match(source, /async function playSelectedExcerpt/)
  assert.match(source, /function stopAtExcerptEnd/)
  assert.match(source, /class="timeline-excerpt-range"/)
  assert.match(source, /Привязка слов расчётная/)
})

test('book and video word navigation prefer exact TTS boundaries', () => {
  assert.match(source, /class="book-word-navigator"/)
  assert.match(source, /currentBookPlayerWord/)
  assert.match(source, /seekBookPlayer\(word\.start\)/)
  assert.match(source, /selectedBookAudioMetadata\.value\.word_timings/)
  assert.match(source, /function applyExactWordTimings/)
  assert.match(source, /chapter\?\.wordTimings \|\| chapter\?\.word_timings/)
  assert.match(source, /wordTimings: Array\.isArray\(timing\?\.word_timings\)/)
})

test('ordinary book playback is not mistaken for a finished text excerpt', () => {
  const start = source.indexOf('function stopAtExcerptEnd()')
  const end = source.indexOf('function scrollTimelineToTime', start)
  const guard = source.slice(start, end)
  assert.match(guard, /selectedEnd === null/)
  assert.ok(
    guard.indexOf('selectedEnd === null') < guard.indexOf('Number(selectedEnd)'),
    'null excerpt guard must run before numeric conversion',
  )
  assert.match(source, /playing\.value \|\| !!\(master && !master\.paused\)/)
})
