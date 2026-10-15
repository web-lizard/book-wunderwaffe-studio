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
  if (!rootClass?.includes('comic-noir')) throw new Error(`Comic noir filter is not active: ${rootClass}`)
  if (!(await page.locator('.telegram-qr').count())) throw new Error('Telegram QR is missing from comic viewer')
  if (!(await page.locator('.scene-corner-bottom').count())) throw new Error('Compact timeline is missing from comic viewer')
  if (await page.locator('.comic-controls').count()) throw new Error('Oversized comic controls are still present')

  const checkpoints = [
    0.8,
    165.254 + 90,
    893.756 + 235,
    1971.397 + 1,
    3344.156 + 1,
    3344.156 + 454,
    4176.366 + 1,
    4176.366 + 331,
    4824.653 + 1,
    4824.653 + 713,
    4824.653 + 1308,
    6244.579 + 1,
    6244.579 + 866,
    6244.579 + 1536,
    7848.552 + 1,
    7848.552 + 701,
    7848.552 + 1391,
  ]
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

  await page.keyboard.press('Escape')
  if (await page.locator('.studio.comic-theater').count()) throw new Error('Escape did not close theater mode')
  await page.locator('.transport-scrubber').evaluate(input => {
    input.value = '1'
    input.dispatchEvent(new Event('input', { bubbles: true }))
  })
  const before = await page.locator('audio').first().evaluate(audio => audio.currentTime)
  await page.locator('.transport .play-button').click()
  await page.waitForTimeout(700)
  const after = await page.locator('audio').first().evaluate(audio => audio.currentTime)
  if (!(Number(after) > Number(before))) throw new Error(`Playback did not advance: ${before} -> ${after}`)
  await page.locator('.transport .play-button').click()
  if (errors.length) throw new Error(`Browser errors: ${errors.join(' | ')}`)
  console.log(JSON.stringify({ ok: true, observed, playback: { before, after }, screenshot }, null, 2))
} finally {
  await browser.close()
}
