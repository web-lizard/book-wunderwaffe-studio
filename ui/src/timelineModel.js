function accessor(value, fallback) {
  if (typeof value === 'function') return value
  const key = value || fallback
  return item => item?.[key]
}

function finiteNumber(value, fallback = 0) {
  const number = Number(value)
  return Number.isFinite(number) ? number : fallback
}

export function buildTimelineChapters(chapters, duration = 0) {
  const sorted = [...(chapters || [])].sort((left, right) => (
    finiteNumber(left?.start_seconds) - finiteNumber(right?.start_seconds)
  ))
  return sorted.map((chapter, index) => {
    const start = finiteNumber(chapter?.start_seconds)
    const nextStart = sorted[index + 1]?.start_seconds
    const proposedEnd = (nextStart ?? Number(chapter?.end_seconds)) || finiteNumber(duration) || start + 60
    return {
      ...chapter,
      index,
      start_seconds: start,
      end_seconds: Math.max(start + 0.1, finiteNumber(proposedEnd, start + 60)),
    }
  })
}

export function createIntervalIndex(items, { start = 'start', end = 'end' } = {}) {
  const startOf = accessor(start, 'start')
  const endOf = accessor(end, 'end')
  const entries = (items || []).map((item, order) => {
    const intervalStart = finiteNumber(startOf(item))
    return {
      item,
      order,
      start: intervalStart,
      end: finiteNumber(endOf(item), intervalStart),
    }
  }).sort((left, right) => left.start - right.start || left.order - right.order)

  const prefixMaxEnds = new Float64Array(entries.length)
  let maximum = -Infinity
  for (let index = 0; index < entries.length; index += 1) {
    maximum = Math.max(maximum, entries[index].end)
    prefixMaxEnds[index] = maximum
  }
  return { entries, items: entries.map(entry => entry.item), prefixMaxEnds }
}

export function findStartedIndex(index, timeValue) {
  const entries = index?.entries || []
  if (!entries.length) return -1
  const time = Math.max(0, finiteNumber(timeValue))
  let low = 0
  let high = entries.length - 1
  let result = 0
  while (low <= high) {
    const middle = (low + high) >> 1
    if (entries[middle].start <= time) {
      result = middle
      low = middle + 1
    } else {
      high = middle - 1
    }
  }
  return result
}

export function findActiveInterval(index, timeValue, predicate = null) {
  const entries = index?.entries || []
  if (!entries.length) return null
  const time = Math.max(0, finiteNumber(timeValue))
  let candidate = findStartedIndex(index, time)
  while (candidate >= 0) {
    if (index.prefixMaxEnds[candidate] <= time) break
    const entry = entries[candidate]
    if (time >= entry.start && time < entry.end && (!predicate || predicate(entry.item))) {
      return entry.item
    }
    candidate -= 1
  }
  return null
}

export function visibleIntervals(index, startValue, endValue) {
  const entries = index?.entries || []
  if (!entries.length) return []
  const rangeStart = Math.max(0, finiteNumber(startValue))
  const rangeEnd = Math.max(rangeStart, finiteNumber(endValue, rangeStart))

  let low = 0
  let high = entries.length - 1
  let last = -1
  while (low <= high) {
    const middle = (low + high) >> 1
    if (entries[middle].start <= rangeEnd) {
      last = middle
      low = middle + 1
    } else {
      high = middle - 1
    }
  }
  if (last < 0 || index.prefixMaxEnds[last] < rangeStart) return []

  low = 0
  high = last
  let first = last
  while (low <= high) {
    const middle = (low + high) >> 1
    if (index.prefixMaxEnds[middle] >= rangeStart) {
      first = middle
      high = middle - 1
    } else {
      low = middle + 1
    }
  }

  const visible = []
  for (let position = first; position <= last; position += 1) {
    const entry = entries[position]
    if (entry.end >= rangeStart) visible.push(entry.item)
  }
  return visible
}

export function buildTimelineVisualClips(chapters) {
  const clips = []
  for (const chapter of chapters || []) {
    const chapterStart = finiteNumber(chapter?.start_seconds)
    const chapterEnd = finiteNumber(chapter?.end_seconds, chapterStart)
    for (const insert of chapter?.visualInserts || []) {
      const start = chapterStart + finiteNumber(insert?.startTime)
      const end = Math.min(chapterEnd, start + Math.max(0, finiteNumber(insert?.duration)))
      if (end <= start) continue
      clips.push({
        ...insert,
        chapterId: chapter.id,
        chapterTitle: chapter.title,
        start,
        end,
      })
    }
  }
  return clips.sort((left, right) => left.start - right.start || left.end - right.end)
}

export function timelineViewportRange(viewport, duration) {
  const total = Math.max(0.1, finiteNumber(duration, 0.1))
  const contentWidth = Math.max(1, finiteNumber(viewport?.contentWidth || viewport?.width, 1))
  const viewportWidth = Math.max(1, finiteNumber(viewport?.width, 1))
  const overscan = viewportWidth * 0.75
  const left = Math.max(0, finiteNumber(viewport?.left) - overscan)
  const right = Math.min(contentWidth, finiteNumber(viewport?.left) + viewportWidth + overscan)
  return { start: left / contentWidth * total, end: right / contentWidth * total }
}
