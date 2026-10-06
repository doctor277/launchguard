import { describe, expect, it, vi } from 'vitest'
import { get, mapLimited } from './client'
import { json } from '../test/fixtures'
import { configureApiSession } from '../auth/session'

describe('API client', () => {
  it('returns null for HTTP 204 rather than attempting JSON parsing', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(null, 204)))
    expect(await get('/services/id/incidents/current', new AbortController().signal)).toBeNull()
  })
  it('preserves a structured backend error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ message: 'Invalid window' }, 400)))
    await expect(get('/services/id/metrics', new AbortController().signal)).rejects.toMatchObject({ status: 400, message: 'Invalid window' })
  })
  it('attaches the bearer access token without persisting it in localStorage', async () => {
    const fetch = vi.fn().mockResolvedValue(json([]))
    vi.stubGlobal('fetch', fetch)
    configureApiSession('access-token', vi.fn())
    await get('/services', new AbortController().signal)
    expect(fetch).toHaveBeenCalledWith('/api/services', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer access-token' }),
    }))
    expect(window.localStorage.length).toBe(0)
  })
  it('expires the local session on HTTP 401', async () => {
    const expired = vi.fn()
    configureApiSession('expired-token', expired)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ message: 'decoder detail' }, 401)))
    await expect(get('/services', new AbortController().signal)).rejects.toMatchObject({
      status: 401, message: 'Your session has expired. Sign in again.',
    })
    expect(expired).toHaveBeenCalledOnce()
  })
  it('preserves a structured HTTP 403 without expiring the session', async () => {
    const expired = vi.fn()
    configureApiSession('viewer-token', expired)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ message: 'Operator role required' }, 403)))
    await expect(get('/services', new AbortController().signal)).rejects.toMatchObject({
      status: 403, message: 'Operator role required',
    })
    expect(expired).not.toHaveBeenCalled()
  })
  it('handles non-JSON proxy errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>Bad gateway</html>', { status: 502 })))
    await expect(get('/services', new AbortController().signal)).rejects.toMatchObject({ status: 502, message: 'Request failed (HTTP 502)' })
  })
  it('bounds metrics fan-out to four concurrent requests while preserving order', async () => {
    let active = 0
    let maximum = 0
    const results = await mapLimited(Array.from({ length: 15 }, (_, i) => i), async i => {
      active++; maximum = Math.max(maximum, active)
      await new Promise(resolve => setTimeout(resolve, 1))
      active--; return i * 2
    }, new AbortController().signal)
    expect(maximum).toBe(4)
    expect(results).toEqual(Array.from({ length: 15 }, (_, i) => i * 2))
  })
  it('does not start queued work after cancellation', async () => {
    const controller = new AbortController()
    controller.abort()
    const task = vi.fn()
    await expect(mapLimited([1, 2], task, controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
    expect(task).not.toHaveBeenCalled()
  })
  it('times out an unresponsive request', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn((_url: string, options: RequestInit) => new Promise((_resolve, reject) => options.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError'))))))
    const assertion = expect(get('/services', new AbortController().signal)).rejects.toThrow('Request timed out after 10 seconds.')
    await vi.advanceTimersByTimeAsync(10_000)
    await assertion
  })
})
