// The Python scene compiler owns timing, chapter clipping, overlap resolution,
// transitions and layout. Preview only interpolates the returned keyframes.
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
  let visual = null
  while (low <= high) {
    const middle = (low + high) >> 1
    const clip = clips[middle]
    if (time < clip.start) high = middle - 1
    else if (time >= clip.end) low = middle + 1
    else { visual = clip; break }
  }
  return { mode: visual?.mode || 'normal', visual, opacity: visual ? opacityAt(visual, time) : 0 }
}

export function percentRect(rect) {
  return { left: `${rect.x}%`, top: `${rect.y}%`, width: `${rect.w}%`, height: `${rect.h}%` }
}
