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
  const visual = plan?.clips?.find(clip => time >= clip.start && time < clip.end) || null
  return { mode: visual?.mode || 'normal', visual, opacity: visual ? opacityAt(visual, time) : 0 }
}

export function percentRect(rect) {
  return { left: `${rect.x}%`, top: `${rect.y}%`, width: `${rect.w}%`, height: `${rect.h}%` }
}
