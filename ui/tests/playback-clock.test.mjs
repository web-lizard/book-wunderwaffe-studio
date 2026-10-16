import assert from 'node:assert/strict'
import test from 'node:test'

import { createFrameGate } from '../src/playbackClock.js'

test('playback frame gate limits reactive commits while accepting forced updates', () => {
  const gate = createFrameGate({ framesPerSecond: 30 })
  const accepted = [0, 8, 17, 34, 50, 68].filter(timestamp => gate.shouldCommit(timestamp))
  assert.deepEqual(accepted, [0, 34, 68])
  assert.equal(gate.shouldCommit(69, { force: true }), true)
  assert.equal(gate.shouldCommit(70), false)
  gate.reset()
  assert.equal(gate.shouldCommit(1), true)
  assert.ok(Math.abs(gate.intervalMs - 1000 / 30) < 0.001)
})
