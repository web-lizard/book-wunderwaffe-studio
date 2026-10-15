import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
const scenePlan = readFileSync(new URL('../src/scenePlan.js', import.meta.url), 'utf8')

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
  assert.match(app, /updateTimelineViewport/)
  assert.match(app, /showTimelineClipThumbnail/)
  assert.match(scenePlan, /while \(low <= high\)/)
  assert.doesNotMatch(scenePlan, /clips\?\.find/)
})

test('opening an existing edition does not immediately rewrite its full payload', () => {
  const switchProject = app.slice(app.indexOf('async function switchProject'), app.indexOf('async function setWorkspace'))
  assert.doesNotMatch(switchProject, /publishVideoCompatibility/)
})
