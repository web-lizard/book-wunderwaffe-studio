export const DEFAULT_PLAYBACK_UI_FPS = 30

export function createFrameGate({ framesPerSecond = DEFAULT_PLAYBACK_UI_FPS } = {}) {
  const fps = Math.max(1, Math.min(120, Number(framesPerSecond) || DEFAULT_PLAYBACK_UI_FPS))
  const intervalMs = 1000 / fps
  let lastFrameAt = -Infinity

  return {
    shouldCommit(timestamp, { force = false } = {}) {
      const now = Number(timestamp)
      if (!Number.isFinite(now)) return false
      if (force || now < lastFrameAt || now - lastFrameAt >= intervalMs) {
        lastFrameAt = now
        return true
      }
      return false
    },
    reset() {
      lastFrameAt = -Infinity
    },
    get intervalMs() {
      return intervalMs
    },
  }
}
