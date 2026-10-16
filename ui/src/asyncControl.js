export function createLatestSaveCoordinator({ capture, persist, isCurrent }) {
  if (![capture, persist, isCurrent].every(value => typeof value === 'function')) {
    throw new TypeError('capture, persist and isCurrent must be functions')
  }
  let active = null

  async function execute() {
    while (true) {
      const snapshot = capture()
      const result = await persist(snapshot)
      if (isCurrent(snapshot)) return { snapshot, result }
    }
  }

  return {
    save() {
      if (active) return active
      active = execute().then(
        result => { active = null; return result },
        error => { active = null; throw error },
      )
      return active
    },
    get running() { return active !== null },
  }
}

export function createSerialPoller(task, { intervalMs = 1000, retryMs = intervalMs, onError = null } = {}) {
  if (typeof task !== 'function') throw new TypeError('poll task must be a function')
  let timer = null
  let generation = 0
  let active = false
  let running = false

  const stop = () => {
    generation += 1
    active = false
    if (timer !== null) clearTimeout(timer)
    timer = null
  }

  const start = ({ immediate = true } = {}) => {
    stop()
    active = true
    const ownGeneration = generation

    const schedule = delay => {
      if (!active || ownGeneration !== generation) return
      timer = setTimeout(tick, Math.max(0, Number(delay) || 0))
    }
    const tick = async () => {
      if (!active || ownGeneration !== generation) return
      timer = null
      if (running) {
        schedule(Math.min(25, Math.max(1, Number(intervalMs) || 1)))
        return
      }
      running = true
      let keepGoing = true
      let delay = intervalMs
      try {
        keepGoing = await task()
      } catch (error) {
        delay = retryMs
        onError?.(error)
      } finally {
        running = false
      }
      if (keepGoing === false) {
        active = false
        return
      }
      schedule(delay)
    }

    schedule(immediate ? 0 : intervalMs)
  }

  return {
    start,
    stop,
    get active() { return active },
    get running() { return running },
  }
}

export function createPointerGestureController(eventTarget) {
  if (!eventTarget?.addEventListener || !eventTarget?.removeEventListener) {
    throw new TypeError('pointer gesture target must implement EventTarget')
  }
  let cleanup = null

  const stop = () => {
    cleanup?.()
    cleanup = null
  }

  const start = (move, onEnd = null) => {
    if (typeof move !== 'function') throw new TypeError('pointer move handler must be a function')
    stop()
    const end = event => {
      const release = cleanup
      cleanup = null
      release?.()
      onEnd?.(event)
    }
    cleanup = () => {
      eventTarget.removeEventListener('pointermove', move)
      eventTarget.removeEventListener('pointerup', end)
      eventTarget.removeEventListener('pointercancel', end)
    }
    eventTarget.addEventListener('pointermove', move)
    eventTarget.addEventListener('pointerup', end)
    eventTarget.addEventListener('pointercancel', end)
  }

  return {
    start,
    stop,
    get active() { return cleanup !== null },
  }
}
