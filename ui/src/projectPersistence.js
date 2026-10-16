export function createProjectSnapshotWriter({
  apiRequest,
  storage,
  getOnline,
  onEdition = null,
}) {
  if (typeof apiRequest !== 'function' || typeof getOnline !== 'function') {
    throw new TypeError('apiRequest and getOnline must be functions')
  }

  return async function persistProjectSnapshot(snapshot) {
    let browserCached = true
    try {
      storage?.setItem('book-wunderwaffe-project', JSON.stringify(snapshot.payload))
    } catch (error) {
      browserCached = false
      if (!getOnline()) throw new Error(`Локальное хранилище недоступно: ${error.message}`)
    }

    if (!getOnline()) return { browserCached }
    let editionId = snapshot.editionId
    if (snapshot.projectUuid && !editionId) {
      const edition = await apiRequest(`/projects/${encodeURIComponent(snapshot.projectUuid)}/video-editions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: snapshot.payload.title || null }),
      })
      editionId = edition.id
      onEdition?.(editionId, snapshot.projectUuid)
    }
    const query = snapshot.projectUuid
      ? `?project_uuid=${encodeURIComponent(snapshot.projectUuid)}&edition_id=${encodeURIComponent(editionId)}`
      : ''
    const saved = await apiRequest(`/editor-project${query}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(snapshot.payload),
    })
    if (saved.edition_id) onEdition?.(saved.edition_id, snapshot.projectUuid)
    return { browserCached }
  }
}
