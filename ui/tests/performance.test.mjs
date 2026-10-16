import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
const scenePlan = readFileSync(new URL('../src/scenePlan.js', import.meta.url), 'utf8')
const asyncControl = readFileSync(new URL('../src/asyncControl.js', import.meta.url), 'utf8')
const timelineModel = readFileSync(new URL('../src/timelineModel.js', import.meta.url), 'utf8')
const playbackClock = readFileSync(new URL('../src/playbackClock.js', import.meta.url), 'utf8')
const style = readFileSync(new URL('../src/style.css', import.meta.url), 'utf8')

test('large visual projects use thumbnails and render only the active full-size frame', () => {
  assert.match(app, /project-thumbnail/)
  assert.match(app, /assetThumbnailUrl/)
  assert.match(app, /assetPreviewUrl/)
  assert.match(app, /loading="lazy"/)
  assert.match(app, /v-if="visualScene\.visual"/)
  assert.doesNotMatch(app, /v-for="clip in previewVisualClips"/)
})

test('timeline work is viewport-limited and scene lookup is logarithmic', () => {
  assert.match(app, /visibleTimelineVisualClips/)
  assert.match(app, /visibleTimelineChapters/)
  assert.match(app, /visibleTimelineScenes/)
  assert.match(app, /visibleMusicClips/)
  assert.match(app, /updateTimelineViewport/)
  assert.match(app, /showTimelineClipThumbnail/)
  assert.match(scenePlan, /while \(low <= high\)/)
  assert.match(timelineModel, /export function createIntervalIndex/)
  assert.match(timelineModel, /prefixMaxEnds/)
  assert.match(timelineModel, /export function visibleIntervals/)
  assert.doesNotMatch(scenePlan, /clips\?\.find/)
})

test('playback bounds high-frequency root updates and memoizes large rows', () => {
  assert.match(app, /createFrameGate\(\{ framesPerSecond: 10 \}\)/)
  assert.match(app, /playbackUiGate\.shouldCommit/)
  assert.match(app, /function updatePlaybackDom/)
  assert.match(app, /translate3d\(/)
  assert.match(app, /visualSceneLayer\.value\.style\.opacity/)
  assert.match(app, /v-memo=/)
  assert.match(app, /wordsByParagraph/)
  assert.match(style, /content-visibility:\s*auto/)
  assert.match(style, /contain-intrinsic-size:/)
  assert.match(playbackClock, /DEFAULT_PLAYBACK_UI_FPS = 30/)
})

test('opening an existing edition does not immediately rewrite its full payload', () => {
  const switchProject = app.slice(app.indexOf('async function switchProject'), app.indexOf('async function setWorkspace'))
  assert.doesNotMatch(switchProject, /publishVideoCompatibility/)
})

test('editor serializes saves and background polling instead of racing requests', () => {
  assert.match(app, /createLatestSaveCoordinator/)
  assert.match(app, /projectSaver\.save\(\)/)
  assert.match(app, /snapshot\.revision === videoEditRevision/)
  assert.match(app, /createSerialPoller/)
  assert.match(app, /startTtsPolling/)
  assert.match(app, /startRenderPolling/)
  assert.doesNotMatch(app, /setInterval\(async/)
  const flush = app.slice(app.indexOf('async function flushActiveWorkspace'), app.indexOf('async function performProjectSwitch'))
  assert.match(flush, /if \(dirty\.value\)/)
  assert.doesNotMatch(flush, /dirty\.value && activeVideoEditionId/)
})

test('editor cleans up cancellable gestures and uses bounded API requests', () => {
  assert.match(app, /layerGestures\.stop\(\)/)
  assert.match(asyncControl, /addEventListener\('pointercancel', end\)/)
  assert.match(app, /createApiClient\(API\)/)
  assert.match(app, /apiRequest\('\/health', \{ timeoutMs: 4000 \}\)/)
})
