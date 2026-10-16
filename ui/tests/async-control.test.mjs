import assert from 'node:assert/strict'
import test from 'node:test'

import { createLatestSaveCoordinator, createSerialPoller } from '../src/asyncControl.js'

const wait = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds))

test('latest save coordinator coalesces callers and persists edits made during a save', async () => {
  let revision = 1
  const writes = []
  let releaseFirst
  const firstWrite = new Promise(resolve => { releaseFirst = resolve })
  const coordinator = createLatestSaveCoordinator({
    capture: () => ({ revision, value: `draft-${revision}` }),
    persist: async snapshot => {
      writes.push(snapshot)
      if (snapshot.revision === 1) await firstWrite
      return snapshot.value
    },
    isCurrent: snapshot => snapshot.revision === revision,
  })

  const first = coordinator.save()
  const second = coordinator.save()
  assert.strictEqual(first, second)
  revision = 2
  releaseFirst()
  const saved = await first

  assert.deepEqual(writes.map(item => item.revision), [1, 2])
  assert.equal(saved.snapshot.revision, 2)
  assert.equal(coordinator.running, false)
})

test('latest save coordinator releases the queue after a failed write', async () => {
  let attempts = 0
  const coordinator = createLatestSaveCoordinator({
    capture: () => ({ revision: 1 }),
    persist: async () => {
      attempts += 1
      if (attempts === 1) throw new Error('disk full')
      return 'saved'
    },
    isCurrent: () => true,
  })
  await assert.rejects(coordinator.save(), /disk full/)
  assert.equal(coordinator.running, false)
  assert.equal((await coordinator.save()).result, 'saved')
})

test('serial poller never overlaps slow asynchronous requests', async () => {
  let concurrent = 0
  let maximum = 0
  let calls = 0
  let finished
  const done = new Promise(resolve => { finished = resolve })
  const poller = createSerialPoller(async () => {
    calls += 1
    concurrent += 1
    maximum = Math.max(maximum, concurrent)
    await wait(12)
    concurrent -= 1
    if (calls === 3) { finished(); return false }
    return true
  }, { intervalMs: 1 })

  poller.start()
  await done
  await wait(5)
  assert.equal(calls, 3)
  assert.equal(maximum, 1)
  assert.equal(poller.active, false)
})

test('stopping a poller prevents a completed request from scheduling another one', async () => {
  let calls = 0
  let release
  const blocked = new Promise(resolve => { release = resolve })
  const poller = createSerialPoller(async () => {
    calls += 1
    await blocked
    return true
  }, { intervalMs: 1 })

  poller.start()
  await wait(5)
  poller.stop()
  release()
  await wait(10)
  assert.equal(calls, 1)
})
