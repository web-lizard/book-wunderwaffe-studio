import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { sceneAt } from '../src/scenePlan.js'

test('preview consumes the export compiler plan, including seeking and chapter boundaries', () => {
  const project = { duration_seconds: 60, materials: [{ id: 'a', type: 'image' }], chapters: [
    { id: 'one', start_seconds: 0, end_seconds: 30, visualInserts: [{ assetId: 'a', startTime: 10, duration: 40 }] },
    { id: 'two', start_seconds: 30, end_seconds: 60, visualInserts: [{ assetId: 'a', startTime: 0, duration: 5 }] },
  ] }
  const times = [0, 10, 10.1, 10.375, 11, 29.5, 29.99, 30, 30.375, 31, 34.9, 35, 60]
  const result = spawnSync(process.env.PYTHON || 'python', ['-c',
    'import json,sys; from bookender.visual_inserts import compile_scene_plan,scene_at; p,t=json.load(sys.stdin); plan=compile_scene_plan(p); print(json.dumps([plan,[scene_at(plan,x) for x in t]]))'],
  { cwd: fileURLToPath(new URL('../../', import.meta.url)), input: JSON.stringify([project, times]), encoding: 'utf8' })
  assert.equal(result.status, 0, result.stderr)
  const [plan, expected] = JSON.parse(result.stdout)
  times.forEach((time, index) => assert.deepEqual(sceneAt(plan, time), expected[index]))
})
