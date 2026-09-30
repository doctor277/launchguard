import { act, renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { usePolling } from './usePolling'

describe('polling', () => {
  it('waits for completion before scheduling another refresh and cancels on unmount', async () => {
    vi.useFakeTimers()
    let finish!: (value: string) => void
    const load = vi.fn((signal: AbortSignal) => { expect(signal.aborted).toBe(false); return new Promise<string>(resolve => { finish = resolve }) })
    const hook = renderHook(() => usePolling('test', load))
    await act(() => vi.advanceTimersByTimeAsync(30_000))
    expect(load).toHaveBeenCalledTimes(1)
    await act(async () => { finish('first'); await Promise.resolve() })
    expect(hook.result.current.data).toBe('first')
    await act(() => vi.advanceTimersByTimeAsync(10_000))
    expect(load).toHaveBeenCalledTimes(2)
    const lastSignal = load.mock.calls[1]![0]
    hook.unmount()
    expect(lastSignal.aborted).toBe(true)
  })
  it('retains previous data with an explicit stale error on a failed refresh', async () => {
    vi.useFakeTimers()
    const load = vi.fn().mockResolvedValueOnce('first').mockRejectedValueOnce(new Error('Offline'))
    const hook = renderHook(() => usePolling('test', load))
    await act(() => vi.advanceTimersByTimeAsync(0))
    await act(() => vi.advanceTimersByTimeAsync(10_000))
    expect(hook.result.current.data).toBe('first')
    expect(hook.result.current.error).toBe('Offline')
  })
  it('pauses automatic network requests in hidden tabs', async () => {
    vi.useFakeTimers()
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    const load = vi.fn().mockResolvedValue('first')
    renderHook(() => usePolling('test', load))
    await act(() => vi.advanceTimersByTimeAsync(30_000))
    expect(load).toHaveBeenCalledTimes(1)
  })
})
