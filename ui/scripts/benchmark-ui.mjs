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

function fixture() {
  const materials = Array.from({ length: 24 }, (_, index) => ({
    id: `image-${index}`,
    name: `Image ${index}`,
    type: 'image',
    serverPath: `fixture/image-${index}.png`,
    status: 'ready',
  }))
  const chapters = Array.from({ length: chapterCount }, (_, chapterIndex) => {
    const start = chapterIndex * 60
    return {
      id: `chapter-${chapterIndex}`,
      chapterId: chapterIndex + 1,
      title: `Chapter ${chapterIndex + 1}`,
      text: `Chapter ${chapterIndex + 1} benchmark text `.repeat(20),
      start_seconds: start,
      end_seconds: start + 60,
      sourceStartSeconds: start,
      sourceEndSeconds: start + 60,
      visualInserts: Array.from({ length: insertsPerChapter }, (_, insertIndex) => ({
        id: `visual-${chapterIndex}-${insertIndex}`,
        assetId: `image-${(chapterIndex + insertIndex) % materials.length}`,
        type: 'image',
        startTime: insertIndex * 5.5,
        duration: 5,
        transition: 'cut',
        transitionDuration: 0,
      })),
    }
  })
  const editor = {
    schemaVersion: 2,
    title: 'UI benchmark',
    duration_seconds: chapterCount * 60,
    materials,
    chapters,
    scenes: Array.from({ length: sceneCount }, (_, index) => ({
      id: `scene-${index}`,
      name: `Scene ${index + 1}`,
      start: index * chapterCount * 60 / sceneCount,
      end: (index + 1) * chapterCount * 60 / sceneCount,
    })),
    musicClips: Array.from({ length: musicClipCount }, (_, index) => ({
      id: `music-${index}`,
      assetId: 'missing-benchmark-audio',
      start: index * chapterCount * 60 / musicClipCount,
      end: (index + 1) * chapterCount * 60 / musicClipCount,
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

async function installRoutes(page) {
  const record = fixture()
  await page.route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    let json = {}
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

  return {
    readyMs,
    zoomMs,
    scrollMedianMs: percentile(scrollMs),
    scrollP95Ms: percentile(scrollMs, 0.95),
    seek120Ms: seekMs,
    renderedVisualClips,
    domNodes: await page.locator('*').count(),
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
