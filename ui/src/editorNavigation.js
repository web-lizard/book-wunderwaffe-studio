export const CHAPTER_COLORS = [
  '#23e6a8', '#3dc5f5', '#9466ff', '#ffcc52', '#ff5688', '#30d9ed',
  '#b2f465', '#ff8a42', '#d861ff', '#658eff', '#50e9cf', '#ffe052',
  '#f65088', '#66fa77', '#369fff', '#21e8a1',
]

export function chapterColor(index) {
  return CHAPTER_COLORS[Math.abs(Number(index) || 0) % CHAPTER_COLORS.length]
}

// Move only the reader's own viewport. scrollIntoView also scrolls hidden
// ancestors, which can shift the entire editor behind the modal.
export function followWord(container, word) {
  if (!container || !word) return false
  const viewport = container.getBoundingClientRect()
  const rect = word.getBoundingClientRect()
  const margin = Math.min(48, container.clientHeight * 0.15)
  if (rect.top >= viewport.top + margin && rect.bottom <= viewport.bottom - margin) return false
  container.scrollTop = Math.max(0, container.scrollTop + rect.top - viewport.top - container.clientHeight * 0.35)
  return true
}
