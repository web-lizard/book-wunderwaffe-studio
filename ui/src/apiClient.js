export class ApiError extends Error {
  constructor(message, { status = 0, code = 'api_error', details = null } = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = details
  }
}

function responseDetail(data, status) {
  const detail = typeof data?.detail === 'string' ? data.detail : data?.detail?.message
  return detail || data?.message || `HTTP ${status}`
}

export function createApiClient(baseUrl, { fetchImpl = globalThis.fetch, defaultTimeoutMs = 30000 } = {}) {
  if (typeof fetchImpl !== 'function') throw new TypeError('fetch implementation is required')

  return async function apiRequest(path, options = {}) {
    const { timeoutMs = defaultTimeoutMs, signal: externalSignal, ...fetchOptions } = options
    const controller = new AbortController()
    let timedOut = false
    const abortFromExternal = () => controller.abort(externalSignal?.reason)
    if (externalSignal?.aborted) abortFromExternal()
    else externalSignal?.addEventListener('abort', abortFromExternal, { once: true })
    const timer = Number(timeoutMs) > 0
      ? setTimeout(() => { timedOut = true; controller.abort() }, Number(timeoutMs))
      : null

    try {
      try {
        const response = await fetchImpl(`${baseUrl}${path}`, { ...fetchOptions, signal: controller.signal })
        const text = await response.text()
        let data = null
        try { data = text ? JSON.parse(text) : {} } catch { data = { detail: text } }
        if (!response.ok) {
          throw new ApiError(responseDetail(data, response.status), {
            status: response.status,
            code: 'http_error',
            details: data,
          })
        }
        return data
      } catch (error) {
        if (error instanceof ApiError) throw error
        if (controller.signal.aborted) {
          throw new ApiError(
            timedOut ? 'Сервер не ответил вовремя' : 'Запрос отменён',
            { code: timedOut ? 'timeout' : 'aborted' },
          )
        }
        throw new ApiError(`Сервер недоступен: ${error?.message || error}`, { code: 'network_error' })
      }
    } finally {
      if (timer !== null) clearTimeout(timer)
      externalSignal?.removeEventListener('abort', abortFromExternal)
    }
  }
}
