import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
const model = readFileSync(new URL('../src/editorModel.js', import.meta.url), 'utf8')
const visuals = readFileSync(new URL('../src/ChapterVisual.vue', import.meta.url), 'utf8')
const styles = readFileSync(new URL('../src/style.css', import.meta.url), 'utf8')
const visualStyles = readFileSync(new URL('../src/visualInserts.css', import.meta.url), 'utf8')

test('classic projects remain the default while comic mode is project-specific', () => {
  assert.match(model, /presentationMode:\s*'classic'/)
  assert.match(app, /project\.presentationMode === 'comic'/)
  assert.match(app, /v-model="project\.presentationMode"/)
})

test('comic viewer keeps cinematic captions with the compact legacy timeline', () => {
  assert.match(app, /class="comic-viewer-chrome"/)
  assert.match(app, /class="comic-caption"/)
  assert.match(app, /class="scene-corner scene-corner-bottom"/)
  assert.match(app, /class="telegram-qr"/)
  assert.match(app, /comicTelegramVisible/)
  assert.match(app, /6500/)
  assert.doesNotMatch(app, /class="comic-controls"/)
  assert.doesNotMatch(app, /comicControlsTimer/)
  assert.match(app, /toggleComicTheater/)
  assert.match(styles, /\.comic-theater > \.topbar/)
  assert.match(styles, /\.comic-theater-close/)
  assert.match(styles, /\.comic-chapter-title strong[\s\S]*text-shadow/)
  assert.match(styles, /\.comic-caption[\s\S]*text-align:\s*left/)
  assert.match(styles, /\.comic-edition\.comic-noir \.scene-grade/)
  assert.match(styles, /comicAnalogBand/)
})

test('comic illustrations are full-bleed and keep the reusable visual timeline', () => {
  assert.match(app, /:comic="isComicEdition"/)
  assert.match(app, /visualScene\.visual\?\.id === clip\.id/)
  assert.match(visuals, /comicMotionClass/)
  assert.match(visualStyles, /chapter-visual-content\.comic \.chapter-visual-image/)
  assert.match(visualStyles, /object-fit:\s*cover/)
  assert.match(visualStyles, /animation:\s*comic-ken-burns-1[\s\S]*infinite alternate/)
})
