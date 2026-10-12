import { test, expect } from '@playwright/test'

function wav(seconds, frequency = 330) {
  const rate = 16000, count = seconds * rate
  const buffer = Buffer.alloc(44 + count * 2)
  buffer.write('RIFF'); buffer.writeUInt32LE(buffer.length - 8, 4); buffer.write('WAVEfmt ', 8)
  buffer.writeUInt32LE(16, 16); buffer.writeUInt16LE(1, 20); buffer.writeUInt16LE(1, 22)
  buffer.writeUInt32LE(rate, 24); buffer.writeUInt32LE(rate * 2, 28)
  buffer.writeUInt16LE(2, 32); buffer.writeUInt16LE(16, 34); buffer.write('data', 36); buffer.writeUInt32LE(count * 2, 40)
  for (let i = 0; i < count; i++) buffer.writeInt16LE(Math.round(2400 * Math.sin(i * frequency * 2 * Math.PI / rate)), 44 + i * 2)
  return buffer
}

async function openEditor(page) {
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const chapters = Array.from({ length: 3 }, (_, i) => ({
    id: i + 1, title: `Глава ${i + 1}. ${['Начало пути', 'Очень длинное название для проверки устойчивой геометрии интерфейса', 'Возвращение'][i]}`,
    content: Array.from({ length: 90 }, (_, p) => `Абзац ${p + 1}. Читаем книгу спокойно и проверяем каждое слово в длинном тексте главы.`).join('\n\n'),
    content_hash: `hash-${i}`, position: i + 1, order_index: i, is_zero: false,
  }))
  const assets = chapters.map((chapter, i) => ({ id: i + 1, chapter_id: chapter.id, title: 'Голос',
    file_path: `fixture/voice-${i}.wav`, duration: 20, generation_status: 'ready', is_active: true, is_stale: false,
    source_text_hash: chapter.content_hash, version_number: 1, voice: 'ru-RU-DmitryNeural', file_size: 100000 }))
  const editor = {
    schemaVersion: 2, title: 'Тестовая книга', materials: [
      { id: 'music', name: 'Музыка.wav', type: 'audio', role: 'music', serverPath: 'fixture/music.wav', status: 'ready' },
      { id: 'music-2', name: 'Вторая музыка.wav', type: 'audio', role: 'music', serverPath: 'fixture/music-2.wav', status: 'ready' },
    ],
    musicAssetId: 'music', music: { enabled: true, loop: true, volume: 0.16, bass: 0, mid: 0, treble: 0 },
    musicClips: [
      { id: 'clip1', assetId: 'music', start: 0, end: 6, sourceOffset: 0, volume: 1 },
      { id: 'clip2', assetId: 'music', start: 8, end: 25, sourceOffset: 0, volume: 0.5 },
      { id: 'clip3', assetId: 'music-2', start: 25, end: 60, sourceOffset: 1, volume: 1 },
    ],
    chapters: chapters.map((chapter, i) => ({ id: `chapter-${chapter.id}`, chapterId: chapter.id, title: chapter.title,
      text: chapter.content, start_seconds: i * 20, end_seconds: (i + 1) * 20, sourceStartSeconds: i * 20, sourceEndSeconds: (i + 1) * 20 })),
    scenes: [], captions: { enabled: true, wordsPerCard: 14 }, layers: {},
  }
  const record = { uuid: 'fixture-book', title: 'Тестовая книга', author: 'Автор', project_kind: 'book',
    book: { title: 'Тестовая книга', author: 'Автор' }, chapters, audio_assets: assets,
    visual_assets: [], tts_settings: {}, video_editions: [{ id: 1, settings: editor }] }
  const audio = wav(60), music = wav(4, 660)
  const exports = []
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname
    if (path.includes('/media')) {
      const body = url.search.includes('music') ? music : audio
      const range = request.headers().range?.match(/bytes=(\d+)-(\d*)/)
      if (range) {
        const start = Number(range[1]), end = Math.min(body.length - 1, Number(range[2]) || body.length - 1)
        return route.fulfill({ status: 206, contentType: 'audio/wav', headers: { 'Accept-Ranges': 'bytes', 'Content-Range': `bytes ${start}-${end}/${body.length}` }, body: body.subarray(start, end + 1) })
      }
      return route.fulfill({ contentType: 'audio/wav', body })
    }
    let json = {}
    if (path.endsWith('/health')) json = { ok: true, version: 'test' }
    else if (path === '/api/projects') json = { projects: [record], active_project_uuid: record.uuid }
    else if (path.endsWith('/open') || path === '/api/projects/fixture-book') json = record
    else if (path.endsWith('/video-audio/master')) json = { file_path: 'fixture/master.wav', duration: 60, file_size: audio.length, source_asset_ids: [1, 2, 3],
      chapters: chapters.map((chapter, i) => ({ chapter_id: chapter.id, start_seconds: i * 20, end_seconds: (i + 1) * 20 })), missing_chapter_ids: [] }
    else if (path === '/api/waveform') json = { samples: Array(120).fill(0.4) }
    else if (path.endsWith('/tts-jobs')) json = { jobs: [] }
    else if (path.endsWith('/audio-export')) { exports.push(request.postDataJSON()); json = { id: 'audio-job', status: 'running', progress: 0.1, stage: 'Глава 1/2' } }
    else if (path === '/api/jobs/audio-job') json = { id: 'audio-job', status: 'done', progress: 1, stage: 'Аудиокнига готова', download_url: '/api/download-fixture.mp3' }
    else if (path.includes('/chapters/')) json = chapters.find(chapter => path.endsWith(`/${chapter.id}`)) || []
    return route.fulfill({ json })
  })
  await page.goto('/')
  await expect(page.locator('.loading-screen')).toHaveCount(0)
  await expect(page.locator('.chapter-book-list > button')).toHaveCount(3)
  return { errors, exports }
}

async function openVideo(page) {
  await page.getByRole('tab', { name: 'Видео', exact: true }).click()
  await expect(page.locator('.chapter-clip')).toHaveCount(3)
  await expect(page.locator('.transport-button.play-button')).toBeEnabled()
  await page.waitForFunction(() => document.querySelector('.studio > audio')?.readyState >= 2)
}

async function seek(page, seconds) {
  await page.locator('.transport-scrubber').evaluate((input, value) => {
    input.value = value; input.dispatchEvent(new Event('input', { bubbles: true }))
  }, String(seconds))
}

test('reader keeps the outer layout fixed and respects manual scrolling', async ({ page }, testInfo) => {
  const { errors } = await openEditor(page)
  await openVideo(page)
  await page.locator('.text-navigator-shortcut').click()
  const modal = page.locator('.text-navigator-modal'), reader = page.locator('.text-navigator-copy')
  await modal.evaluate(element => Promise.all(element.getAnimations().map(animation => animation.finished)))
  const before = await modal.boundingBox()
  await page.getByRole('button', { name: '▶ Слушать', exact: true }).click()
  for (const seconds of [5, 10, 15, 18]) {
    await seek(page, seconds)
    await expect.poll(() => reader.evaluate(el => el.scrollTop)).toBeGreaterThan(0)
    expect(await modal.boundingBox()).toEqual(before)
    expect(await page.locator('.studio').evaluate(el => el.scrollTop)).toBe(0)
    expect(await page.locator('.text-navigator-layout').evaluate(el => el.scrollTop)).toBe(0)
  }
  await reader.hover(); await page.mouse.wheel(0, -650)
  await expect(page.getByRole('button', { name: 'Следить за текстом', exact: true })).toHaveAttribute('aria-pressed', 'false')
  await reader.evaluate(el => { el.scrollTop = 0 })
  await seek(page, 16)
  expect(await reader.evaluate(el => el.scrollTop)).toBe(0)
  await page.getByRole('button', { name: 'Следить за текстом', exact: true }).click()
  await expect.poll(() => reader.evaluate(el => el.scrollTop)).toBeGreaterThan(0)
  await page.screenshot({ path: testInfo.outputPath('reader.png') })
  expect(errors).toEqual([])
})

test('music resumes after a gap, a source change, seeking, and workspace remount', async ({ page }) => {
  const { errors } = await openEditor(page)
  await openVideo(page)
  const music = page.locator('.studio > audio').nth(1)
  await page.locator('.play-button').click()
  await expect.poll(() => music.evaluate(el => !el.paused)).toBe(true)
  await seek(page, 7)
  await expect.poll(() => music.evaluate(el => el.paused)).toBe(true)
  await seek(page, 9)
  await expect.poll(() => music.evaluate(el => !el.paused)).toBe(true)
  await expect.poll(() => music.evaluate(el => el.volume)).toBeCloseTo(0.08, 2)
  await seek(page, 26)
  await expect.poll(() => music.evaluate(el => !el.paused && el.currentSrc.includes('music-2'))).toBe(true)
  await page.locator('.play-button').click()
  await page.getByRole('tab', { name: 'Книга', exact: true }).click()
  await openVideo(page)
  await page.locator('.play-button').click()
  await expect.poll(() => music.evaluate(el => !el.paused && el.currentTime > 0)).toBe(true)
  await page.locator('.music-mixer-shortcut').click()
  await expect(page.locator('.music-eq-status')).toContainText('EQ активен')
  expect(errors).toEqual([])
})

test('chapter colors agree across workspaces and chapter navigation seeks and zooms', async ({ page }, testInfo) => {
  const { errors } = await openEditor(page)
  const colors = await page.locator('.chapter-book-list > button').evaluateAll(els => els.map(el => el.style.getPropertyValue('--chapter-color')))
  expect(new Set(colors).size).toBe(3)
  await openVideo(page)
  expect(await page.locator('.chapter-clip').evaluateAll(els => els.map(el => el.style.getPropertyValue('--chapter-color')))).toEqual(colors)
  await page.getByRole('button', { name: 'По главам', exact: true }).click()
  await page.getByRole('button', { name: 'Следующая глава', exact: true }).click()
  await expect(page.getByLabel('Глава на таймлайне')).toHaveValue('chapter-2')
  await expect.poll(() => page.locator('.transport-scrubber').inputValue()).toBe('20')
  expect(await page.locator('.timeline-content').evaluate(el => el.offsetWidth)).toBeGreaterThan(2000)
  await page.getByRole('button', { name: 'Предыдущая глава', exact: true }).click()
  await expect(page.getByLabel('Глава на таймлайне')).toHaveValue('chapter-1')
  await page.screenshot({ path: testInfo.outputPath('chapters.png') })
  expect(errors).toEqual([])
})

test('MP3 export stays visible during mouse interaction until rendering finishes', async ({ page }) => {
  const { errors } = await openEditor(page)
  let finished = false
  let polls = 0
  await page.route('**/api/jobs/audio-job', route => {
    polls++
    return route.fulfill({ json: { id: 'audio-job', status: finished ? 'done' : 'running',
      progress: finished ? 1 : 0.3, stage: finished ? 'Аудиокнига готова' : 'Сборка MP3',
      download_url: finished ? '/api/download-fixture.mp3' : '' } })
  })
  await page.getByRole('button', { name: 'Экспорт книги', exact: true }).click()
  const modal = page.getByRole('dialog', { name: 'Экспорт книги' })
  await page.getByRole('button', { name: 'Экспортировать MP3', exact: true }).click()
  await expect(modal.getByRole('status')).toContainText('Сборка MP3')
  for (const [x, y] of [[720, 480], [5, 5], [1430, 950], [720, 480]]) {
    await page.mouse.move(x, y, { steps: 10 })
    await expect(modal).toBeVisible()
  }
  await page.mouse.click(5, 5)
  await expect(modal).toBeVisible()
  await expect(modal.getByRole('button', { name: 'Закрыть', exact: true })).toBeDisabled()
  await expect.poll(() => polls).toBeGreaterThan(1)
  await expect(modal).toBeVisible()
  finished = true
  await expect(modal.getByRole('link', { name: 'Скачать аудиокнигу' })).toBeVisible()
  await modal.getByRole('button', { name: 'Закрыть', exact: true }).click()
  await expect(modal).toHaveCount(0)
  expect(errors).toEqual([])
})

test('MP3 export sends only selected chapters, mix, format and bitrate and shows download', async ({ page }) => {
  const { errors, exports } = await openEditor(page)
  await page.getByRole('button', { name: 'Экспорт книги', exact: true }).click()
  await page.locator('.audio-export-chapters input').nth(1).uncheck()
  await page.locator('.audio-export-options select').nth(0).selectOption('chapters')
  await page.locator('.audio-export-options select').nth(1).selectOption('320')
  await page.getByRole('button', { name: 'Экспортировать MP3', exact: true }).click()
  await expect(page.getByRole('link', { name: 'Скачать аудиокнигу' })).toBeVisible()
  expect(exports).toHaveLength(1)
  expect(exports[0]).toMatchObject({ chapter_ids: [1, 3], mode: 'chapters', bitrate: 320, include_music: true })
  expect(exports[0].editor_project.musicClips).toHaveLength(3)
  expect(errors).toEqual([])
})
