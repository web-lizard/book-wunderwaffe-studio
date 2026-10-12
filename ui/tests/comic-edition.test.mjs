import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
const visuals = readFileSync(new URL('../src/ChapterVisual.vue', import.meta.url), 'utf8')
const styles = readFileSync(new URL('../src/style.css', import.meta.url), 'utf8')

test('classic projects remain the default while comic mode is project-specific', () => {
  assert.match(app, /presentationMode:\s*'classic'/)
  assert.match(app, /project\.presentationMode === 'comic'/)
  assert.match(app, /v-model="project\.presentationMode"/)
})

test('comic viewer replaces editor chrome with cinematic captions and controls', () => {
  assert.match(app, /class="comic-viewer-chrome"/)
  assert.match(app, /class="comic-caption"/)
  assert.match(app, /class="comic-controls"/)
  assert.match(app, /toggleComicTheater/)
  assert.match(app, /comicControlsTimer/)
  assert.match(styles, /\.comic-theater > \.topbar/)
  assert.match(styles, /\.comic-controls-visible \.comic-controls/)
})

test('comic illustrations are full-bleed and keep the reusable visual timeline', () => {
  assert.match(app, /:comic="isComicEdition"/)
  assert.match(app, /visualScene\.visual\?\.id === clip\.id/)
  assert.match(visuals, /:class="\{ comic \}"/)
  assert.match(styles, /chapter-visual-content\.comic \.chapter-visual-image/)
  assert.match(styles, /object-fit:\s*cover/)
})
