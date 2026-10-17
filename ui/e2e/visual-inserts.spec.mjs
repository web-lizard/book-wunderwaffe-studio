import { test, expect } from '@playwright/test'
import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../../', import.meta.url))
const venv = fileURLToPath(new URL('../../.venv/Scripts/python.exe', import.meta.url))
const python = process.env.PYTHON || (existsSync(venv) ? venv : 'python')

function engine(path, payload) {
  // Exercise the real API functions/scene compiler, without touching a user's project.
  const fn = path.endsWith('scene-plan') ? 'editor_scene_plan' : 'place_editor_visuals'
  const result = spawnSync(python, ['-c', `import json,sys,os,tempfile,logging
with tempfile.TemporaryDirectory() as task_dir:
 os.environ['BOOKENDER_USER_DATA'] = task_dir
 os.environ['BOOKENDER_DATABASE'] = os.path.join(task_dir, 'bookender.db')
 from suviren_q_server import ${fn}
 print(json.dumps(${fn}(json.load(sys.stdin))))
 logging.shutdown()`],
    { cwd: root, input: JSON.stringify(payload), encoding: 'utf8', env: { ...process.env, PYTHONUTF8: '1' } })
  if (result.status) throw new Error(result.stderr)
  return JSON.parse(result.stdout)
}

function audioFixture() {
  const rate = 8000, count = 60 * rate, data = Buffer.alloc(44 + count * 2)
  data.write('RIFF'); data.writeUInt32LE(data.length - 8, 4); data.write('WAVEfmt ', 8)
  data.writeUInt32LE(16, 16); data.writeUInt16LE(1, 20); data.writeUInt16LE(1, 22)
  data.writeUInt32LE(rate, 24); data.writeUInt32LE(rate * 2, 28)
  data.writeUInt16LE(2, 32); data.writeUInt16LE(16, 34); data.write('data', 36); data.writeUInt32LE(count * 2, 40)
  for (let i = 0; i < count; i++) data.writeInt16LE(Math.round(1800 * Math.sin(i * 330 * Math.PI * 2 / rate)), 44 + i * 2)
  return data
}

async function openFixture(page, options = {}) {
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  let saved = {
    schemaVersion: 2, title: 'Видеороман', presentationMode: options.presentationMode || 'classic', audioAssetId: 'voice', coverAssetId: 'cover', backgroundAssetId: 'cover',
    duration_seconds: 60, glitch: false, captions: { enabled: true },
    materials: [
      { id: 'voice', type: 'audio', name: 'Озвучка.wav', serverPath: 'fixture/voice.wav' },
      { id: 'cover', type: 'image', name: 'Обложка', serverPath: 'fixture/cover.png' },
      { id: 'art', type: 'image', name: 'Атмосфера', serverPath: 'fixture/art.png' },
      { id: 'comic', type: 'image', name: 'Кадр комикса', serverPath: 'fixture/comic.png' },
    ],
    chapters: [
      { id: 'one', title: 'Глава первая', start_seconds: 0, end_seconds: 40, imageAssetId: 'art', text: 'Текст звучащей книги. Слушатель видит слова и иллюстрации.',
        visualInserts: options.crossfadePair ? [
          { id: 'comic-old', assetId: 'art', type: 'image', startTime: 0, duration: 20, transition: 'crossfade', transitionDuration: .75 },
          { id: 'comic-new', assetId: 'comic', type: 'image', startTime: 20, duration: 20, transition: 'crossfade', transitionDuration: .75 },
        ] : options.presentationMode === 'comic' ? [{ id: 'comic-opening', assetId: 'comic', type: 'image', startTime: 0, duration: 40, transition: 'crossfade', transitionDuration: .75 }] : [] },
      { id: 'two', title: 'Короткая глава', start_seconds: 40, end_seconds: 60, text: 'Снова обычная композиция.' },
    ], scenes: [{ id: 'scene', name: 'Оформление', start: 0, end: 60 }],
  }
  const audio = audioFixture()
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname
    let json = {}
    if (path.includes('/media')) {
      if (url.href.includes('.png')) {
        const isCover = url.href.includes('cover'), isComic = url.href.includes('comic')
        return route.fulfill({ contentType: 'image/svg+xml', body: `<svg xmlns="http://www.w3.org/2000/svg" width="${isComic ? 300 : 240}" height="${isComic ? 300 : 480}" viewBox="0 0 240 480"><rect width="240" height="480" fill="${isCover ? '#503555' : isComic ? '#c59555' : '#254d63'}"/><circle cx="150" cy="140" r="65" fill="#d6c39c"/><path d="M0 450L100 190L170 350L220 250L240 480H0Z" fill="#182d37"/></svg>` })
      }
      const range = request.headers().range?.match(/bytes=(\d+)-(\d*)/)
      if (range) {
        const start = Number(range[1]), end = Math.min(audio.length - 1, Number(range[2]) || audio.length - 1)
        return route.fulfill({ status: 206, contentType: 'audio/wav', headers: { 'Accept-Ranges': 'bytes', 'Content-Range': `bytes ${start}-${end}/${audio.length}` }, body: audio.subarray(start, end + 1) })
      }
      return route.fulfill({ contentType: 'audio/wav', body: audio })
    }
    const record = { uuid: 'visual-fixture', title: 'Видеороман', project_kind: 'video', chapters: [], video_editions: [{ id: 1, settings: saved }] }
    if (path === '/api/health') json = { ok: true }
    else if (path === '/api/projects') json = { projects: [record], active_project_uuid: record.uuid }
    else if (path.endsWith('/open')) json = record
    else if (path === '/api/editor-project' && request.method() === 'POST') {
      saved = request.postDataJSON(); json = { ok: true, edition_id: 1 }
    } else if (path === '/api/scene-plan' || path === '/api/visual-inserts/auto-place') {
      expect(request.headers()['content-type']).toContain('application/json')
      json = engine(path, request.postDataJSON())
    } else if (path === '/api/waveform') json = { samples: Array(60).fill(.3) }
    return route.fulfill({ json })
  })
  await page.goto('/')
  await expect(page.locator('.loading-screen')).toHaveCount(0, { timeout: 15000 })
  await expect(page.locator('.chapter-clip')).toHaveCount(2)
  await page.waitForFunction(() => document.querySelector('.studio audio')?.readyState >= 2)
  return { errors, saved: () => saved }
}

test('comic edition uses full-bleed art, literary subtitles and the compact theater control', async ({ page }) => {
  const fixture = await openFixture(page, { presentationMode: 'comic' })
  await expect(page.locator('.chapter-visual-content.comic')).toHaveCount(1)
  await expect(page.locator('.comic-caption')).toContainText('Текст звучащей книги')
  await expect(page.locator('.telegram-qr')).toHaveCount(0)
  await page.getByRole('button', { name: 'Смотреть Comic Edition' }).click()
  await expect(page.locator('.studio')).toHaveClass(/comic-theater/)
  await expect(page.locator('.topbar')).toBeHidden()
  await expect(page.locator('.comic-theater-close')).toBeVisible()
  await expect(page.locator('.comic-controls')).toHaveCount(0)
  await page.keyboard.press('Escape')
  await expect(page.locator('.studio')).not.toHaveClass(/comic-theater/)
  expect(fixture.errors).toEqual([])
})

test('adjacent comic slides crossfade directly over each other', async ({ page }) => {
  const fixture = await openFixture(page, { presentationMode: 'comic', crossfadePair: true })
  await seek(page, 20.375)
  const layers = page.locator('.chapter-visual-content.comic')
  await expect(layers).toHaveCount(2)
  const opacities = await layers.evaluateAll(elements => elements.map(element => Number(getComputedStyle(element).opacity)))
  expect(opacities[0]).toBeCloseTo(1, 2)
  expect(opacities[1]).toBeGreaterThan(.4)
  expect(opacities[1]).toBeLessThan(.6)
  await expect(page.locator('.visual-scene-layer')).toHaveCSS('opacity', '1')
  expect(fixture.errors).toEqual([])
})

async function seek(page, time) {
  await page.locator('.transport-scrubber').evaluate((input, value) => {
    input.value = value; input.dispatchEvent(new Event('input', { bubbles: true }))
  }, String(time))
}

async function editTime(page, label, value) {
  await page.getByLabel(label, { exact: true }).fill(value)
  await page.getByLabel(label, { exact: true }).press('Tab')
}

test('legacy project, add visual, seek, persistent overlays, playback and reopen', async ({ page }, testInfo) => {
  const fixture = await openFixture(page)
  await expect(page.locator('.scene-frame')).toHaveAttribute('data-mode', 'normal')
  await expect(page.locator('.visual-insert-clip')).toHaveCount(0)
  const qr = await page.locator('.telegram-qr').boundingBox()
  const progress = await page.locator('.scene-corner-bottom').boundingBox()
  await page.locator('.play-button').click()
  await page.locator('.studio audio').first().evaluate(audio => {
    window.visualAudioInterruptions = []
    for (const type of ['pause', 'emptied']) audio.addEventListener(type, () => window.visualAudioInterruptions.push(type))
  })
  await page.getByRole('button', { name: '＋ Визуал', exact: true }).click()
  await page.getByRole('button', { name: 'Добавить иллюстрации' }).click()
  await expect(page.locator('.visual-insert-clip')).toHaveCount(1)
  await seek(page, 10)
  await expect(page.locator('.scene-frame')).toHaveAttribute('data-mode', 'chapter_visual')
  await expect(page.locator('.reading-caption')).toBeHidden()
  expect(await page.locator('.telegram-qr').boundingBox()).toEqual(qr)
  expect(await page.locator('.scene-corner-bottom').boundingBox()).toEqual(progress)
  await expect(page.locator('.telegram-qr')).toBeVisible()
  await expect(page.locator('.chapter-visual-image')).toBeVisible()
  await expect.poll(() => page.locator('.chapter-visual-image').evaluate(img => img.naturalWidth)).toBeGreaterThan(0)
  await expect(page.locator('.chapter-visual-badge')).toHaveText('Глава первая')
  expect(await page.locator('.chapter-visual-image').evaluate(img => getComputedStyle(img).objectFit)).toBe('contain')
  expect(await page.locator('.studio audio').first().evaluate(audio => audio.paused)).toBe(false)
  expect(await page.evaluate(() => window.visualAudioInterruptions)).toEqual([])
  await page.locator('.play-button').click()
  await page.screenshot({ path: testInfo.outputPath('illustration.png') })
  await seek(page, 36)
  await expect(page.locator('.scene-frame')).toHaveAttribute('data-mode', 'normal')
  await expect(page.locator('.reading-caption')).toBeVisible()
  await page.keyboard.press('Control+s')
  await expect.poll(() => fixture.saved().chapters[0].visualInserts?.length).toBe(1)
  const inserts = structuredClone(fixture.saved().chapters[0].visualInserts)
  await page.reload()
  await expect(page.locator('.visual-insert-clip')).toHaveCount(1)
  await seek(page, 12)
  await expect(page.locator('.scene-frame')).toHaveAttribute('data-mode', 'chapter_visual')
  expect(fixture.saved().chapters[0].visualInserts).toEqual(inserts)
  expect(fixture.errors).toEqual([])
  expect(await page.evaluate(() => window.__BOOK_WUNDERWAFFE_ERROR__)).toBeUndefined()
})

test('multiple images, drag, trim, boundary, short chapter and delete', async ({ page }) => {
  const fixture = await openFixture(page)
  await page.getByRole('button', { name: '＋ Визуал', exact: true }).click()
  await page.getByRole('checkbox', { name: 'Кадр комикса', exact: true }).check()
  await page.getByRole('button', { name: 'Добавить иллюстрации' }).click()
  await expect(page.locator('.visual-insert-clip')).toHaveCount(2)
  await seek(page, 10)
  await expect(page.locator('.scene-frame')).toHaveAttribute('data-mode', 'chapter_visual')
  await seek(page, 20)
  await expect(page.locator('.scene-frame')).toHaveAttribute('data-mode', 'normal')
  await seek(page, 28)
  await expect(page.locator('.visual-insert-clip').nth(1)).toHaveClass(/active/)
  await page.locator('.magnet-toggle').click()
  const clip = page.locator('.visual-insert-clip').first()
  const oldStart = await page.getByLabel('Появление иллюстрации', { exact: true }).inputValue()
  const box = await clip.boundingBox()
  await page.mouse.move(box.x + 20, box.y + box.height / 2); await page.mouse.down()
  await page.mouse.move(box.x + 48, box.y + box.height / 2, { steps: 5 }); await page.mouse.up()
  await expect(page.getByLabel('Появление иллюстрации', { exact: true })).not.toHaveValue(oldStart)
  const oldDuration = await page.getByLabel('Длительность иллюстрации', { exact: true }).inputValue()
  const edge = await clip.locator('.scene-trim-handle.end').boundingBox()
  await page.mouse.move(edge.x + edge.width / 2, edge.y + edge.height / 2); await page.mouse.down()
  await page.mouse.move(edge.x - 25, edge.y + edge.height / 2, { steps: 5 }); await page.mouse.up()
  await expect(page.getByLabel('Длительность иллюстрации', { exact: true })).not.toHaveValue(oldDuration)
  await editTime(page, 'Появление иллюстрации', '00:00:38')
  await editTime(page, 'Длительность иллюстрации', '00:00:10')
  await seek(page, 39)
  await expect(page.locator('.scene-frame')).toHaveAttribute('data-mode', 'chapter_visual')
  await seek(page, 40)
  await expect(page.locator('.scene-frame')).toHaveAttribute('data-mode', 'normal')
  await page.locator('.chapter-clip').nth(1).click()
  await page.getByRole('button', { name: '＋ Иллюстрация', exact: true }).click()
  await page.getByRole('checkbox', { name: 'Кадр комикса', exact: true }).check()
  await page.getByRole('button', { name: 'Добавить иллюстрации' }).click()
  await expect(page.locator('.visual-insert-clip')).toHaveCount(3)
  await seek(page, 50)
  await expect(page.locator('.scene-frame')).toHaveAttribute('data-mode', 'chapter_visual')
  await page.getByRole('button', { name: 'Удалить иллюстрацию', exact: true }).click()
  await expect(page.locator('.visual-insert-clip')).toHaveCount(2)
  await expect(page.locator('.scene-frame')).toHaveAttribute('data-mode', 'normal')
  expect(fixture.errors).toEqual([])
  expect(await page.evaluate(() => window.__BOOK_WUNDERWAFFE_ERROR__)).toBeUndefined()
})
