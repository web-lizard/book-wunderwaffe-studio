import assert from 'node:assert/strict'
import test from 'node:test'

import { createProjectSnapshotWriter } from '../src/projectPersistence.js'

test('project writer creates an edition, publishes the snapshot and updates its owner', async () => {
  const requests = []
  const editions = []
  const storage = new Map()
  const write = createProjectSnapshotWriter({
    apiRequest: async (path, options) => {
      requests.push({ path, options })
      return path.endsWith('/video-editions') ? { id: 42 } : { edition_id: 42 }
    },
    storage: { setItem: (key, value) => storage.set(key, value) },
    getOnline: () => true,
    onEdition: (id, uuid) => editions.push([id, uuid]),
  })
  const result = await write({ projectUuid: 'book one', editionId: null, payload: { title: 'Книга' } })
  assert.equal(result.browserCached, true)
  assert.deepEqual(JSON.parse(storage.get('book-wunderwaffe-project')), { title: 'Книга' })
  assert.match(requests[0].path, /book%20one\/video-editions$/)
  assert.match(requests[1].path, /edition_id=42/)
  assert.deepEqual(editions, [[42, 'book one'], [42, 'book one']])
})

test('server save survives a full browser cache while offline save reports it', async () => {
  let online = true
  let serverWrites = 0
  const write = createProjectSnapshotWriter({
    apiRequest: async () => { serverWrites += 1; return {} },
    storage: { setItem: () => { throw new Error('quota exceeded') } },
    getOnline: () => online,
  })
  assert.equal((await write({ projectUuid: '', editionId: null, payload: {} })).browserCached, false)
  assert.equal(serverWrites, 1)
  online = false
  await assert.rejects(
    write({ projectUuid: '', editionId: null, payload: {} }),
    /Локальное хранилище недоступно: quota exceeded/,
  )
})
