// The Python scene compiler owns timing, chapter clipping, overlap resolution,
// transitions and layout. Preview interpolates its keyframes and renders the
// active pair during a slide-to-slide crossfade.
export function opacityAt(clip, time) {
  if (time < clip.start || time >= clip.end) return 0
  const frames = clip.opacity
  for (let i = 1; i < frames.length; i++) {
    const [left, a] = frames[i - 1], [right, b] = frames[i]
    if (time < right) return a + (b - a) * (time - left) / Math.max(1e-9, right - left)
  }
  return frames.at(-1)[1]
}

export function sceneAt(plan, time) {
  const clips = plan?.clips || []
  let low = 0
  let high = clips.length - 1
  let latest = -1
  while (low <= high) {
    const middle = (low + high) >> 1
    const clip = clips[middle]
    if (time < clip.start) high = middle - 1
    else { latest = middle; low = middle + 1 }
  }
  const layers = []
  // The compiler only overlaps adjacent clips, so the current and immediately
  // preceding start are sufficient while keeping playback lookup logarithmic.
  for (let index = Math.max(0, latest - 1); index <= latest; index += 1) {
    const visual = clips[index]
    if (visual && visual.start <= time && time < visual.end) {
      layers.push({ visual, opacity: opacityAt(visual, time) })
    }
  }
  const visual = layers.at(-1)?.visual || null
  return {
    mode: visual?.mode || 'normal',
    visual,
    layers,
    opacity: layers.reduce((maximum, layer) => Math.max(maximum, layer.opacity), 0),
  }
}

export function percentRect(rect) {
  return { left: `${rect.x}%`, top: `${rect.y}%`, width: `${rect.w}%`, height: `${rect.h}%` }
}
