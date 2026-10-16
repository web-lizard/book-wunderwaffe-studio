export const AUDIO_EXT = new Set(['mp3', 'wav', 'm4a', 'aac', 'flac', 'ogg', 'opus'])
export const VIDEO_EXT = new Set(['mp4', 'mov', 'm4v', 'webm', 'mkv', 'avi'])
export const IMAGE_EXT = new Set(['png', 'jpg', 'jpeg', 'webp', 'gif', 'bmp'])
export const YOUTUBE_MAX_DURATION_SECONDS = 12 * 60 * 60
export const MIN_CHAPTER_PLAYBACK_RATE = 0.25
export const MAX_CHAPTER_PLAYBACK_RATE = 8

export const RENDER_PRESETS = Object.freeze({
  compact: {
    id: 'compact', label: 'Компактный', videoKbps: 1200, audioKbps: 192,
    videoLabel: '1,2 Мбит/с', audioLabel: '192 кбит/с',
  },
  balanced: {
    id: 'balanced', label: 'Оптимальный', videoKbps: 1800, audioKbps: 192,
    videoLabel: '1,8 Мбит/с', audioLabel: '192 кбит/с',
  },
  youtube_1080p: {
    id: 'youtube_1080p', label: 'YouTube 1080p', videoKbps: 7500, audioKbps: 384,
    videoLabel: 'до 8 Мбит/с', audioLabel: '384 кбит/с',
  },
})

export const RATE_PRESETS = Object.freeze([
  { label: 'Медленно', value: '-25%' },
  { label: 'Немного медленнее', value: '-10%' },
  { label: 'Обычно', value: '+0%' },
  { label: 'Немного быстрее', value: '+15%' },
  { label: 'Быстро', value: '+30%' },
])

export const PITCH_PRESETS = Object.freeze([
  { label: 'Очень низкий', value: '-40Hz' },
  { label: 'Низкий', value: '-25Hz' },
  { label: 'Немного ниже', value: '-10Hz' },
  { label: 'Нормальный', value: '+0Hz' },
  { label: 'Немного выше', value: '+10Hz' },
  { label: 'Высокий', value: '+25Hz' },
  { label: 'Очень высокий', value: '+40Hz' },
])

export const VOLUME_PRESETS = Object.freeze([
  { label: 'Тише', value: '-20%' },
  { label: 'Обычно', value: '+0%' },
  { label: 'Громче', value: '+20%' },
])

export const MUSIC_EQ_BANDS = Object.freeze([
  { key: 'bass', label: 'НЧ' },
  { key: 'mid', label: 'СЧ' },
  { key: 'treble', label: 'ВЧ' },
])

export const DEFAULT_MUSIC_MIX = Object.freeze({
  enabled: true,
  loop: true,
  volume: 0.16,
  bass: 0,
  mid: 0,
  treble: 0,
})

export const VISUALIZER_COLOR_PRESETS = Object.freeze([
  { id: 'spectrum', label: 'Спектр', colors: ['#ffad27', '#d74fe8', '#55ddcf', '#b750da'] },
  { id: 'amber', label: 'Янтарь', colors: ['#ffcf5a', '#ff9d24', '#eb5b2a', '#ffcf5a'] },
  { id: 'violet', label: 'Фиолет', colors: ['#d9a7ff', '#a968f0', '#6533b8', '#c279ff'] },
  { id: 'ocean', label: 'Океан', colors: ['#7ce7ff', '#26a7df', '#2458c6', '#55ddcf'] },
  { id: 'mint', label: 'Мята', colors: ['#c3ffe6', '#55ddcf', '#2ca77e', '#88f0bf'] },
  { id: 'fire', label: 'Огонь', colors: ['#fff071', '#ff9a2e', '#f04444', '#ffcf5a'] },
  { id: 'rose', label: 'Роза', colors: ['#ffd0e4', '#fa73aa', '#b93478', '#ff8ec1'] },
  { id: 'ice', label: 'Лёд', colors: ['#effcff', '#a9e7ff', '#72a8ff', '#c7f4ff'] },
  { id: 'acid', label: 'Кислота', colors: ['#efff62', '#92f542', '#2bcf75', '#d8ff48'] },
  { id: 'mono', label: 'Моно', colors: ['#ffffff', '#c8c8cf', '#777780', '#eeeeef'] },
  { id: 'copper', label: 'Медь', colors: ['#ffd0a1', '#c8783a', '#7e3f25', '#e7a268'] },
  { id: 'night', label: 'Ночь', colors: ['#9aa8ff', '#5c67d9', '#303478', '#7d65d8'] },
])

export const DEFAULT_LAYERS = Object.freeze({
  cover: { visible: true, x: 7, y: 17, w: 27, h: 66 },
  title: { visible: true, x: 39, y: 23, w: 54, h: 31, fontSize: 48, color: '#f4f0e8' },
  visualizer: { visible: true, x: 38, y: 59, w: 56, h: 23, colorPreset: 'spectrum' },
  caption: { visible: true, x: 14, y: 69, w: 72, h: 18, fontSize: 28, fontWeight: 400, backgroundOpacity: 0.68 },
})

export function uid(prefix = 'id') {
  const value = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `${prefix}-${value}`
}

export function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

export function freshProject() {
  return {
    schemaVersion: 2,
    title: 'Новая аудиокнига',
    author: '',
    theme: 'amber',
    presentationMode: 'classic',
    comic: { noirFilter: true, chapterTitleSeconds: 8, kenBurns: true },
    glitch: true,
    renderPreset: 'balanced',
    audioAssetId: null,
    musicAssetId: null,
    videoAssetId: null,
    coverAssetId: null,
    backgroundAssetId: null,
    coverMode: 'cover',
    backgroundBlur: true,
    captions: { enabled: false, wordsPerCard: 14 },
    music: clone(DEFAULT_MUSIC_MIX),
    musicClips: [],
    materials: [],
    chapters: [],
    scenes: [{ id: uid('scene'), name: 'Основная сцена', start: 0, end: 60, backgroundAssetId: null }],
    layers: clone(DEFAULT_LAYERS),
  }
}

export function materialType(fileName = '', mime = '') {
  const extension = fileName.split('.').pop()?.toLowerCase() || ''
  if (mime.startsWith('audio/') || AUDIO_EXT.has(extension)) return 'audio'
  if (mime.startsWith('video/') || VIDEO_EXT.has(extension)) return 'video'
  if (mime.startsWith('image/') || IMAGE_EXT.has(extension)) return 'image'
  return null
}

export function readingCaptionChunks(text, wordsPerCard = 14) {
  const words = String(text || '').trim().split(/\s+/).filter(Boolean)
  const size = Math.max(6, Math.min(28, Number(wordsPerCard) || 14))
  const chunks = []
  for (let index = 0; index < words.length; index += size) chunks.push(words.slice(index, index + size).join(' '))
  return chunks
}

export function parseJsonObject(value) {
  if (value && typeof value === 'object' && !Array.isArray(value)) return value
  try {
    const parsed = JSON.parse(String(value || '{}'))
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch {
    return {}
  }
}

export function canonicalTimelineWord(value) {
  return String(value || '').toLocaleLowerCase('ru-RU').replace(/[^\p{L}\p{N}]+/gu, '')
}

export function applyExactWordTimings(words, exactTimings, start, end) {
  if (!Array.isArray(exactTimings) || !exactTimings.length) return words
  const timings = exactTimings
    .map(timing => ({ text: String(timing?.text || ''), start: Number(timing?.start), end: Number(timing?.end) }))
    .filter(timing => Number.isFinite(timing.start) && Number.isFinite(timing.end) && timing.end >= timing.start)
    .sort((left, right) => left.start - right.start)
  if (!timings.length) return words

  const anchors = new Map()
  let timingIndex = 0
  for (const word of words) {
    const source = canonicalTimelineWord(word.text)
    if (!source) continue
    while (timingIndex < timings.length && !canonicalTimelineWord(timings[timingIndex].text)) timingIndex += 1
    let combined = ''
    let matchEnd = timingIndex - 1
    for (let lookahead = timingIndex; lookahead < Math.min(timings.length, timingIndex + 5); lookahead += 1) {
      combined += canonicalTimelineWord(timings[lookahead].text)
      if (combined === source) { matchEnd = lookahead; break }
      if (!source.startsWith(combined)) break
    }
    if (matchEnd < timingIndex) continue
    anchors.set(word.index, {
      start: Math.max(start, Math.min(end, timings[timingIndex].start)),
      end: Math.max(start, Math.min(end, timings[matchEnd].end)),
    })
    timingIndex = matchEnd + 1
  }
  if (!anchors.size) return words

  const result = words.map(word => ({ ...word }))
  for (const [index, timing] of anchors) {
    result[index].start = timing.start
    result[index].end = Math.max(timing.start + 0.02, timing.end)
    result[index].timing = 'exact'
  }
  let index = 0
  while (index < result.length) {
    if (anchors.has(index)) { index += 1; continue }
    const rangeStart = index
    while (index < result.length && !anchors.has(index)) index += 1
    const rangeEnd = index
    const left = rangeStart > 0 ? result[rangeStart - 1].end : start
    const right = rangeEnd < result.length ? result[rangeEnd].start : end
    const available = Math.max(0.02 * (rangeEnd - rangeStart), right - left)
    const totalWeight = result.slice(rangeStart, rangeEnd).reduce((sum, word) => sum + word.weight, 0)
    let elapsed = 0
    for (let wordIndex = rangeStart; wordIndex < rangeEnd; wordIndex += 1) {
      const word = result[wordIndex]
      word.start = left + elapsed / Math.max(totalWeight, 1) * available
      elapsed += word.weight
      word.end = Math.min(right, Math.max(word.start + 0.02, left + elapsed / Math.max(totalWeight, 1) * available))
    }
  }
  return result
}

export function timedTextWords(value, startValue, endValue, exactTimings = []) {
  if (!value) return []
  const text = String(value)
  const matches = [...text.matchAll(/\S+/gu)]
  if (!matches.length) return []
  const start = Number(startValue) || 0
  const end = Math.max(start + 0.1, Number(endValue) || start + 0.1)
  const weighted = matches.map((match, index) => {
    const word = match[0]
    const letters = word.replace(/[^\p{L}\p{N}]/gu, '').length
    const pause = /[.!?…]["»”’)]*$/u.test(word) ? 5 : /[,;:]["»”’)]*$/u.test(word) ? 2 : 0
    return { index, text: word, sourceIndex: match.index, sourceEnd: match.index + word.length, weight: Math.max(1.8, letters + pause) }
  })
  const totalWeight = weighted.reduce((sum, word) => sum + word.weight, 0)
  let elapsedWeight = 0
  let paragraph = 0
  let previousEnd = 0
  const estimated = weighted.map(word => {
    const gap = text.slice(previousEnd, word.sourceIndex)
    if (word.index > 0 && /\n\s*\n/u.test(gap)) paragraph += 1
    const wordStart = start + elapsedWeight / totalWeight * (end - start)
    elapsedWeight += word.weight
    const wordEnd = start + elapsedWeight / totalWeight * (end - start)
    previousEnd = word.sourceEnd
    return { ...word, paragraph, start: wordStart, end: Math.max(wordStart + 0.02, wordEnd) }
  })
  return applyExactWordTimings(estimated, exactTimings, start, end)
}

export function chapterSourceStart(chapter) {
  const value = Number(chapter?.sourceStartSeconds ?? chapter?.source_start_seconds)
  return Number.isFinite(value) ? Math.max(0, value) : Math.max(0, Number(chapter?.start_seconds) || 0)
}

export function chapterSourceEnd(chapter) {
  const start = chapterSourceStart(chapter)
  const value = Number(chapter?.sourceEndSeconds ?? chapter?.source_end_seconds)
  return Number.isFinite(value) && value > start ? value : Math.max(start + 0.1, Number(chapter?.end_seconds) || start + 0.1)
}

export function chapterSourceDuration(chapter) {
  return Math.max(0.1, chapterSourceEnd(chapter) - chapterSourceStart(chapter))
}

export function chapterTimelineDuration(chapter) {
  return Math.max(0.1, Number(chapter?.end_seconds) - Number(chapter?.start_seconds))
}

export function chapterPlaybackRate(chapter) {
  if (!chapter) return 1
  const calculated = chapterSourceDuration(chapter) / chapterTimelineDuration(chapter)
  return Math.max(MIN_CHAPTER_PLAYBACK_RATE, Math.min(MAX_CHAPTER_PLAYBACK_RATE, calculated || 1))
}

export function chapterTimelineWordTimings(chapter) {
  const timings = chapter?.wordTimings || chapter?.word_timings || []
  if (!Array.isArray(timings) || !timings.length) return []
  const sourceStart = chapterSourceStart(chapter)
  const timelineStart = Number(chapter.start_seconds) || 0
  const rate = chapterPlaybackRate(chapter)
  return timings.map(timing => ({
    ...timing,
    start: timelineStart + (Number(timing.start) - sourceStart) / rate,
    end: timelineStart + (Number(timing.end) - sourceStart) / rate,
  }))
}

export function chapterTimelineWords(chapter) {
  if (!chapter?.text) return []
  return timedTextWords(chapter.text, chapter.start_seconds, chapter.end_seconds, chapterTimelineWordTimings(chapter))
}

export function wordAtTime(words, timeValue) {
  if (!words.length) return null
  const time = Number(timeValue) || 0
  let low = 0
  let high = words.length - 1
  let result = words[0]
  while (low <= high) {
    const middle = Math.floor((low + high) / 2)
    if (words[middle].start <= time) { result = words[middle]; low = middle + 1 } else high = middle - 1
  }
  return result
}

export function parseTimecode(value) {
  if (typeof value === 'number') return Math.max(0, value)
  const text = String(value || '').trim().replace(',', '.')
  if (!text.includes(':')) return Math.max(0, Number(text) || 0)
  const parts = text.split(':').map(Number)
  if (parts.some(part => !Number.isFinite(part))) return 0
  return parts.reduce((total, part) => total * 60 + part, 0)
}

export function formatTime(value, forceHours = false) {
  const seconds = Number.isFinite(Number(value)) ? Math.max(0, Number(value)) : 0
  const whole = Math.floor(seconds)
  const hours = Math.floor(whole / 3600)
  const minutes = Math.floor((whole % 3600) / 60)
  const secs = whole % 60
  if (hours || forceHours) return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(secs).padStart(2, '0')}`
  return `${String(minutes).padStart(2, '0')}:${String(secs).padStart(2, '0')}`
}

export function formatTimecode(value) {
  return formatTime(value, true)
}

export function normalizeChapter(item, index = 0) {
  const start = Number(item.start_seconds ?? parseTimecode(item.start ?? 0)) || 0
  const end = Number(item.end_seconds ?? parseTimecode(item.end ?? 0)) || 0
  const sourceStart = Number(item.sourceStartSeconds ?? item.source_start_seconds ?? start)
  const sourceEnd = Number(item.sourceEndSeconds ?? item.source_end_seconds ?? end)
  const normalizedSourceStart = Number.isFinite(sourceStart) ? Math.max(0, sourceStart) : Math.max(0, start)
  const normalizedSourceEnd = Number.isFinite(sourceEnd) ? Math.max(normalizedSourceStart, sourceEnd) : Math.max(normalizedSourceStart, end)
  return {
    ...item,
    id: item.id || uid('chapter'),
    visualInserts: Array.isArray(item.visualInserts) ? item.visualInserts.filter(clip => clip && clip.assetId).map(clip => ({
      ...clip,
      id: clip.id || uid('visual'),
      type: clip.type || 'image',
      startTime: Math.max(0, Number(clip.startTime) || 0),
      duration: Math.max(0, Number(clip.duration) || 0),
      transition: clip.transition === 'none' ? 'none' : 'crossfade',
      transitionDuration: Math.max(0.5, Math.min(1, Number(clip.transitionDuration) || 0.75)),
    })) : [],
    title: String(item.title || `Глава ${index + 1}`),
    start_seconds: Math.max(0, start),
    end_seconds: Math.max(start, end),
    sourceStartSeconds: normalizedSourceStart,
    sourceEndSeconds: normalizedSourceEnd,
    playbackRate: Math.max(MIN_CHAPTER_PLAYBACK_RATE, Math.min(MAX_CHAPTER_PLAYBACK_RATE, Number(item.playbackRate) || 1)),
  }
}

export function normalizeProject(value, { mediaUrl = () => '' } = {}) {
  const base = freshProject()
  const source = value && typeof value === 'object' ? value : {}
  const normalized = {
    ...base,
    ...source,
    coverMode: source.coverMode === 'background-only' ? 'background-only' : 'cover',
    backgroundBlur: source.backgroundBlur !== false,
    captions: { ...base.captions, ...(source.captions || {}) },
    comic: { ...base.comic, ...(source.comic || {}) },
    music: { ...base.music, ...(source.music || {}) },
    musicClips: Array.isArray(source.musicClips) ? source.musicClips
      .filter(clip => clip && typeof clip === 'object' && clip.assetId)
      .map(clip => {
        const start = Math.max(0, Number(clip.start) || 0)
        return {
          id: clip.id || uid('music-clip'), assetId: clip.assetId, start,
          end: Math.max(start + 0.05, Number(clip.end) || start + 30),
          sourceOffset: Math.max(0, Number(clip.sourceOffset) || 0),
          volume: Math.max(0, Math.min(2, Number.isFinite(Number(clip.volume)) ? Number(clip.volume) : 1)),
          enabled: clip.enabled !== false,
          chapterId: clip.chapterId || null,
          chapterBound: clip.chapterBound === true,
        }
      }) : [],
    renderPreset: RENDER_PRESETS[source.renderPreset] ? source.renderPreset : base.renderPreset,
    materials: Array.isArray(source.materials) ? source.materials.map(asset => ({
      ...asset,
      id: asset.id || uid('asset'),
      status: asset.serverPath ? 'ready' : 'missing',
      progress: asset.serverPath ? 1 : 0,
      src: asset.serverPath ? mediaUrl(asset.serverPath) : '',
    })) : [],
    chapters: Array.isArray(source.chapters) ? source.chapters.map(normalizeChapter) : [],
    scenes: Array.isArray(source.scenes) && source.scenes.length ? source.scenes.map(scene => ({
      id: scene.id || uid('scene'),
      name: scene.name || 'Сцена',
      start: Math.max(0, Number(scene.start) || 0),
      end: Math.max(Number(scene.start) || 0, Number(scene.end) || 60),
      backgroundAssetId: scene.backgroundAssetId || null,
    })) : base.scenes,
    layers: {
      cover: { ...DEFAULT_LAYERS.cover, ...(source.layers?.cover || {}) },
      title: { ...DEFAULT_LAYERS.title, ...(source.layers?.title || {}) },
      visualizer: { ...DEFAULT_LAYERS.visualizer, ...(source.layers?.visualizer || {}) },
      caption: { ...DEFAULT_LAYERS.caption, ...(source.layers?.caption || {}) },
    },
  }
  if (!VISUALIZER_COLOR_PRESETS.some(preset => preset.id === normalized.layers.visualizer.colorPreset)) {
    normalized.layers.visualizer.colorPreset = DEFAULT_LAYERS.visualizer.colorPreset
  }
  if (!Array.isArray(source.musicClips) && normalized.musicAssetId) {
    const legacyEnd = Number(source.duration_seconds || source.durationSeconds || source.audioDuration)
      || normalized.chapters.reduce((maximum, chapter) => Math.max(maximum, Number(chapter.end_seconds) || 0), 0)
      || normalized.scenes.reduce((maximum, scene) => Math.max(maximum, Number(scene.end) || 0), 0)
      || 60
    normalized.musicClips.push({
      id: uid('music-clip'), assetId: normalized.musicAssetId, start: 0,
      end: Math.max(0.1, legacyEnd), sourceOffset: 0, volume: 1, enabled: true,
    })
  }
  return normalized
}
