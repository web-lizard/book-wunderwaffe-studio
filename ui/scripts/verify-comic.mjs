import { chromium } from '@playwright/test'

const baseURL = process.env.BOOKENDER_TEST_URL || 'http://127.0.0.1:5178'
const screenshot = process.env.BOOKENDER_COMIC_SCREENSHOT
const browser = await chromium.launch({ headless: true, args: ['--autoplay-policy=no-user-gesture-required'] })
const page = await browser.newPage({ viewport: { width: 1600, height: 900 } })
const errors = []
page.on('pageerror', error => errors.push(error.message))
page.on('console', message => { if (message.type() === 'error') errors.push(message.text()) })

try {
  await page.goto(baseURL, { waitUntil: 'networkidle' })
  await page.locator('.loading-screen').waitFor({ state: 'detached' })
  await page.getByRole('tab', { name: /Видео/ }).click()
  await page.locator('.scene-frame').waitFor()
  await page.waitForFunction(() => document.querySelector('.scene-frame')?.dataset.mode === 'chapter_visual')
  const rootClass = await page.locator('.studio').getAttribute('class')
  if (!rootClass?.includes('comic-edition')) throw new Error(`Comic mode is not active: ${rootClass}`)
  if (await page.locator('.telegram-qr').count()) throw new Error('Classic QR leaked into comic viewer')

  const checkpoints = [0.8, 32.8, 195.8, 1055.432, 2327.048, 3945.2]
  const observed = []
  for (const time of checkpoints) {
    await page.locator('.transport-scrubber').evaluate((input, value) => {
      input.value = String(value)
      input.dispatchEvent(new Event('input', { bubbles: true }))
    }, time)
    await page.waitForTimeout(120)
    const active = await page.locator('.chapter-visual-content').evaluateAll(nodes => {
      const visible = nodes.find(node => Number.parseFloat(node.style.opacity || '0') > .2)
      return visible?.querySelector('img')?.getAttribute('src') || ''
    })
    if (!active.includes('/images/comic/chapter-')) throw new Error(`No comic frame at ${time}: ${active}`)
    observed.push({ time, frame: active.split('/').at(-1) })
  }

  await page.getByRole('button', { name: 'Смотреть Comic Edition' }).click()
  await page.locator('.studio.comic-theater').waitFor()
  await page.locator('.comic-caption').waitFor()
  if (screenshot) await page.screenshot({ path: screenshot })

  const before = await page.locator('.comic-controls input[type="range"]').inputValue()
  await page.locator('.comic-play').click()
  await page.waitForTimeout(700)
  const after = await page.locator('.comic-controls input[type="range"]').inputValue()
  if (!(Number(after) > Number(before))) throw new Error(`Playback did not advance: ${before} -> ${after}`)
  await page.locator('.comic-play').click()
  await page.keyboard.press('Escape')
  if (await page.locator('.studio.comic-theater').count()) throw new Error('Escape did not close theater mode')
  if (errors.length) throw new Error(`Browser errors: ${errors.join(' | ')}`)
  console.log(JSON.stringify({ ok: true, observed, playback: { before, after }, screenshot }, null, 2))
} finally {
  await browser.close()
}
