import assert from 'node:assert/strict'
import test from 'node:test'

import {
  buildTimelineChapters, buildTimelineVisualClips, createIntervalIndex,
  findActiveInterval, findStartedIndex, timelineViewportRange, visibleIntervals,
} from '../src/timelineModel.js'

test('timeline chapters are sorted and end at the next chapter boundary', () => {
  const chapters = buildTimelineChapters([
    { id: 'late', start_seconds: 20, end_seconds: 99 },
    { id: 'early', start_seconds: 0, end_seconds: 8 },
  ], 50)
  assert.deepEqual(chapters.map(item => [item.id, item.index, item.end_seconds]), [
    ['early', 0, 20], ['late', 1, 99],
  ])
})

test('interval lookup prefers the latest overlapping active item', () => {
  const index = createIntervalIndex([
    { id: 'long', start: 0, end: 20 },
    { id: 'late', start: 10, end: 12 },
    { id: 'disabled', start: 11, end: 18, enabled: false },
  ])
  assert.equal(findStartedIndex(index, 10.5), 1)
  assert.equal(findActiveInterval(index, 11.5)?.id, 'disabled')
  assert.equal(findActiveInterval(index, 11.5, item => item.enabled !== false)?.id, 'late')
  assert.equal(findActiveInterval(index, 15, item => item.enabled !== false)?.id, 'long')
  assert.equal(findActiveInterval(index, 20), null)
})

test('visibility index returns overlaps without scanning unrelated prefixes', () => {
  const index = createIntervalIndex([
    { id: 'old', start: 0, end: 100 },
    { id: 'before', start: 101, end: 110 },
    { id: 'inside', start: 125, end: 130 },
    { id: 'after', start: 151, end: 170 },
  ])
  assert.deepEqual(visibleIntervals(index, 120, 150).map(item => item.id), ['inside'])
  assert.deepEqual(visibleIntervals(index, 90, 126).map(item => item.id), ['old', 'before', 'inside'])
})

test('visual clips are clipped to their chapters and sorted globally', () => {
  const clips = buildTimelineVisualClips([
    { id: 'b', title: 'B', start_seconds: 10, end_seconds: 20,
      visualInserts: [{ id: 'second', startTime: 8, duration: 8 }] },
    { id: 'a', title: 'A', start_seconds: 0, end_seconds: 10,
      visualInserts: [{ id: 'first', startTime: 2, duration: 3 }] },
  ])
  assert.deepEqual(clips.map(item => [item.id, item.start, item.end]), [
    ['first', 2, 5], ['second', 18, 20],
  ])
})

test('viewport range includes overscan and stays inside the duration', () => {
  assert.deepEqual(timelineViewportRange({ left: 500, width: 100, contentWidth: 1000 }, 100), {
    start: 42.5,
    end: 67.5,
  })
})
