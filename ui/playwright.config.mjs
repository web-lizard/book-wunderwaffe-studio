import { defineConfig } from '@playwright/test'
import { existsSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

const cache = process.env.LOCALAPPDATA && join(process.env.LOCALAPPDATA, 'ms-playwright')
const installed = cache && existsSync(cache) ? readdirSync(cache).filter(name => /^chromium-\d+$/.test(name)).sort().reverse()
  .map(name => join(cache, name, 'chrome-win64', 'chrome.exe')).find(existsSync) : undefined

export default defineConfig({
  testDir: './e2e', fullyParallel: false, workers: 1, timeout: 45000,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:5179', viewport: { width: 1440, height: 960 },
    screenshot: 'only-on-failure', trace: 'retain-on-failure',
    launchOptions: { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || installed, args: ['--autoplay-policy=no-user-gesture-required'] },
  },
  webServer: { command: 'npm run dev -- --port 5179', url: 'http://127.0.0.1:5179', reuseExistingServer: false },
})
