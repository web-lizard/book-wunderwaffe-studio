import { chromium } from '@playwright/test'
import { existsSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

const baseURL = process.argv[2] || 'http://127.0.0.1:4178'
const label = process.argv[3] || 'local'
const runs = Math.max(2, Number(process.argv[4]) || 5)
const chapterCount = 120
const insertsPerChapter = 10
const sceneCount = 480
const musicClipCount = 240
const imageCount = 24

function wav(seconds = 4, frequency = 330) {
  const rate = 8000
  const count = seconds * rate
  const buffer = Buffer.alloc(44 + count * 2)
  buffer.write('RIFF'); buffer.writeUInt32LE(buffer.length - 8, 4); buffer.write('WAVEfmt ', 8)
  buffer.writeUInt32LE(16, 16); buffer.writeUInt16LE(1, 20); buffer.writeUInt16LE(1, 22)
  buffer.writeUInt32LE(rate, 24); buffer.writeUInt32LE(rate * 2, 28)
  buffer.writeUInt16LE(2, 32); buffer.writeUInt16LE(16, 34); buffer.write('data', 36)
  buffer.writeUInt32LE(count * 2, 40)
  for (let index = 0; index < count; index += 1) {
    buffer.writeInt16LE(Math.round(1800 * Math.sin(index * frequency * Math.PI * 2 / rate)), 44 + index * 2)
  }
  return buffer
}

function percentile(values, fraction = 0.5) {
  const sorted = [...values].sort((left, right) => left - right)
  return sorted[Math.min(sorted.length - 1, Math.floor((sorted.length - 1) * fraction))]
}

function round(value) {
  return Math.round(value * 10) / 10
}

function browserExecutable() {
  const cache = process.env.LOCALAPPDATA && join(process.env.LOCALAPPDATA, 'ms-playwright')
  if (!cache || !existsSync(cache)) return undefined
  return readdirSync(cache).filter(name => /^chromium-\d+$/.test(name)).sort().reverse()
    .map(name => join(cache, name, 'chrome-win64', 'chrome.exe')).find(existsSync)
}

function fixture({ playback = false } = {}) {
  const totalDuration = playback ? 4 : chapterCount * 60
  const materials = [...(playback ? [{
    id: 'voice', name: 'Voice.wav', type: 'audio', role: 'narration',
    serverPath: 'fixture/voice.wav', status: 'ready',
  }] : []), ...Array.from({ length: imageCount }, (_, index) => ({
    id: `image-${index}`,
    name: `Image ${index}`,
    type: 'image',
    serverPath: `fixture/image-${index}.png`,
    status: 'ready',
  }))]
  const chapters = Array.from({ length: chapterCount }, (_, chapterIndex) => {
    const start = playback ? 0 : chapterIndex * 60
    const end = playback ? totalDuration : start + 60
    return {
      id: `chapter-${chapterIndex}`,
      chapterId: chapterIndex + 1,
      title: `Chapter ${chapterIndex + 1}`,
      text: `Chapter ${chapterIndex + 1} benchmark text `.repeat(20),
      start_seconds: start,
      end_seconds: end,
      sourceStartSeconds: start,
      sourceEndSeconds: end,
      visualInserts: Array.from({ length: insertsPerChapter }, (_, insertIndex) => ({
        id: `visual-${chapterIndex}-${insertIndex}`,
        assetId: `image-${(chapterIndex + insertIndex) % imageCount}`,
        type: 'image',
        startTime: playback ? 0 : insertIndex * 5.5,
        duration: playback ? totalDuration : 5,
        transition: 'cut',
        transitionDuration: 0,
      })),
    }
  })
  const editor = {
    schemaVersion: 2,
    title: 'UI benchmark',
    duration_seconds: totalDuration,
    audioAssetId: playback ? 'voice' : null,
    materials,
    chapters,
    scenes: Array.from({ length: sceneCount }, (_, index) => ({
      id: `scene-${index}`,
      name: `Scene ${index + 1}`,
      start: playback ? 0 : index * totalDuration / sceneCount,
      end: playback ? totalDuration : (index + 1) * totalDuration / sceneCount,
    })),
    musicClips: Array.from({ length: musicClipCount }, (_, index) => ({
      id: `music-${index}`,
      assetId: 'missing-benchmark-audio',
      start: playback ? 0 : index * totalDuration / musicClipCount,
      end: playback ? totalDuration : (index + 1) * totalDuration / musicClipCount,
      sourceOffset: 0,
      volume: 1,
      enabled: true,
    })),
    layers: {},
    captions: { enabled: false, wordsPerCard: 14 },
  }
  return {
    uuid: 'benchmark-project',
    title: 'UI benchmark',
    project_kind: 'video',
    chapters: [],
    video_editions: [{ id: 1, settings: editor }],
  }
}

async function installRoutes(page, options = {}) {
  const record = fixture(options)
  const audio = wav()
  await page.route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    let json = {}
    if (path.includes('/media')) {
      const range = request.headers().range?.match(/bytes=(\d+)-(\d*)/)
      if (range) {
        const start = Number(range[1])
        const end = Math.min(audio.length - 1, Number(range[2]) || audio.length - 1)
        return route.fulfill({
          status: 206,
          contentType: 'audio/wav',
          headers: { 'Accept-Ranges': 'bytes', 'Content-Range': `bytes ${start}-${end}/${audio.length}` },
          body: audio.subarray(start, end + 1),
        })
      }
      return route.fulfill({ contentType: 'audio/wav', body: audio })
    }
    if (path === '/api/health') json = { ok: true, version: 'benchmark' }
    else if (path === '/api/projects') json = { projects: [record], active_project_uuid: record.uuid }
    else if (path.endsWith('/open') || path === `/api/projects/${record.uuid}`) json = record
    else if (path === '/api/scene-plan') json = { clips: [], issues: [] }
    else if (path === '/api/waveform') json = { samples: Array(720).fill(0.35) }
    else if (path === '/api/editor-project') json = { ok: true, edition_id: 1 }
    else if (path.includes('/project-thumbnail/') || path.includes('/project-media/')) {
      return route.fulfill({ status: 204 })
    }
    return route.fulfill({ json })
  })
}

async function measure(page) {
  await installRoutes(page)
  const started = performance.now()
  await page.goto(baseURL, { waitUntil: 'domcontentloaded' })
  await page.locator('.loading-screen').waitFor({ state: 'detached', timeout: 20000 })
  await page.locator('.timeline-content').waitFor({ state: 'visible' })
  const readyMs = performance.now() - started

  const zoomMs = await page.locator('.zoom-control input').evaluate(async input => {
    const startedAt = performance.now()
    input.value = '12'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))
    return performance.now() - startedAt
  })
  const renderedVisualClips = await page.locator('.visual-insert-clip').count()

  const scrollMs = await page.locator('.timeline-scroll').evaluate(async scroller => {
    const samples = []
    const maximum = Math.max(0, scroller.scrollWidth - scroller.clientWidth)
    for (let index = 0; index < 24; index += 1) {
      const startedAt = performance.now()
      scroller.scrollLeft = maximum * index / 23
      scroller.dispatchEvent(new Event('scroll'))
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))
      samples.push(performance.now() - startedAt)
    }
    return samples
  })

  const seekMs = await page.locator('.transport-scrubber').evaluate(async input => {
    const maximum = Number(input.max)
    const startedAt = performance.now()
    for (let index = 0; index < 120; index += 1) {
      input.value = String(maximum * index / 119)
      input.dispatchEvent(new Event('input', { bubbles: true }))
      await Promise.resolve()
    }
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))
    return performance.now() - startedAt
  })

  const domNodes = await page.locator('*').count()

  await page.unroute('**/api/**')
  await installRoutes(page, { playback: true })
  await page.goto(`${baseURL}?benchmark=playback`, { waitUntil: 'domcontentloaded' })
  await page.locator('.loading-screen').waitFor({ state: 'detached', timeout: 20000 })
  await page.locator('.timeline-content').waitFor({ state: 'visible' })
  await page.waitForFunction(() => document.querySelector('.studio > audio')?.readyState >= 2)
  await page.evaluate(() => {
    const target = document.querySelector('.studio')
    window.__uiBenchmarkPlayback = { mutationRecords: 0, mutationBatches: 0, frames: 0 }
    window.__uiBenchmarkObserver = new MutationObserver(records => {
      window.__uiBenchmarkPlayback.mutationBatches += 1
      window.__uiBenchmarkPlayback.mutationRecords += records.length
    })
    window.__uiBenchmarkObserver.observe(target, { attributes: true, childList: true, characterData: true, subtree: true })
    const startedAt = performance.now()
    const countFrame = timestamp => {
      if (timestamp - startedAt > 1000) return
      window.__uiBenchmarkPlayback.frames += 1
      requestAnimationFrame(countFrame)
    }
    requestAnimationFrame(countFrame)
  })
  await page.locator('.play-button').click()
  await page.waitForTimeout(1100)
  const playback = await page.evaluate(() => {
    window.__uiBenchmarkObserver.disconnect()
    return window.__uiBenchmarkPlayback
  })
  await page.locator('.play-button').click()

  return {
    readyMs,
    zoomMs,
    scrollMedianMs: percentile(scrollMs),
    scrollP95Ms: percentile(scrollMs, 0.95),
    seek120Ms: seekMs,
    renderedVisualClips,
    domNodes,
    playbackMutationRecords: playback.mutationRecords,
    playbackMutationBatches: playback.mutationBatches,
    playbackFrames: playback.frames,
  }
}

const executablePath = browserExecutable()
const browser = await chromium.launch({ headless: true, executablePath })
const samples = []
try {
  for (let index = 0; index < runs; index += 1) {
    const page = await browser.newPage({ viewport: { width: 1440, height: 960 } })
    samples.push(await measure(page))
    await page.close()
  }
} finally {
  await browser.close()
}

const metrics = {}
for (const key of Object.keys(samples[0])) {
  metrics[key] = round(percentile(samples.map(sample => sample[key])))
}
process.stdout.write(`${JSON.stringify({
  label, runs, chapterCount, scenes: sceneCount, musicClips: musicClipCount,
  inserts: chapterCount * insertsPerChapter, metrics, samples,
}, null, 2)}\n`)
