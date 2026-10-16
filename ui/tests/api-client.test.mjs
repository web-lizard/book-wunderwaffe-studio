import assert from 'node:assert/strict'
import test from 'node:test'

import { ApiError, createApiClient } from '../src/apiClient.js'

test('api client parses successful JSON and preserves request options', async () => {
  let received
  const request = createApiClient('/api', { fetchImpl: async (url, options) => {
    received = { url, options }
    return new Response(JSON.stringify({ ok: true }), { status: 200 })
  } })
  assert.deepEqual(await request('/health', { method: 'POST' }), { ok: true })
  assert.equal(received.url, '/api/health')
  assert.equal(received.options.method, 'POST')
  assert.ok(received.options.signal instanceof AbortSignal)
})

test('api client exposes backend errors without losing status or details', async () => {
  const request = createApiClient('/api', { fetchImpl: async () => new Response(
    JSON.stringify({ detail: { message: 'Некорректный проект', field: 'title' } }),
    { status: 422 },
  ) })
  await assert.rejects(
    request('/project'),
    error => error instanceof ApiError
      && error.message === 'Некорректный проект'
      && error.status === 422
      && error.details.detail.field === 'title',
  )
})

test('api client aborts a hung request at its deadline', async () => {
  const request = createApiClient('/api', { fetchImpl: (_, options) => new Promise((resolve, reject) => {
    options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true })
  }) })
  await assert.rejects(
    request('/hung', { timeoutMs: 5 }),
    error => error instanceof ApiError && error.code === 'timeout',
  )
})

test('api deadline also covers a response body that never finishes', async () => {
  const request = createApiClient('/api', { fetchImpl: async (_, options) => ({
    ok: true,
    status: 200,
    text: () => new Promise((resolve, reject) => {
      options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true })
    }),
  }) })
  await assert.rejects(
    request('/slow-body', { timeoutMs: 5 }),
    error => error instanceof ApiError && error.code === 'timeout',
  )
})

test('api client turns transport failures into readable network errors', async () => {
  const request = createApiClient('/api', { fetchImpl: async () => { throw new TypeError('connection reset') } })
  await assert.rejects(
    request('/offline'),
    error => error instanceof ApiError
      && error.code === 'network_error'
      && error.message.includes('connection reset'),
  )
})
