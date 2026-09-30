import { useEffect, useState } from 'react'
import { errorMessage } from '../api/client'

export const POLL_INTERVAL_MS = 10_000
export function usePolling<T>(key: string, load: (signal: AbortSignal) => Promise<T>, enabled = true) {
  const [revision, setRevision] = useState(0)
  const [state, setState] = useState<{ key: string; data?: T; error?: string; loading: boolean; updatedAt?: Date }>({ key, loading: true })
  useEffect(() => {
    if (!enabled) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    const run = async (initial = false) => {
      if (controller.signal.aborted) return
      if (initial || document.visibilityState !== 'hidden') {
        setState(previous => ({ ...(previous.key === key ? previous : { key }), loading: true }))
        try {
          const data = await load(controller.signal)
          if (!controller.signal.aborted) setState({ key, data, loading: false, updatedAt: new Date() })
        } catch (error) {
          if (!controller.signal.aborted) setState(previous => ({ ...(previous.key === key ? previous : { key }), loading: false, error: errorMessage(error) }))
        }
      }
      if (!controller.signal.aborted) timer = setTimeout(() => void run(), POLL_INTERVAL_MS)
    }
    void run(true)
    return () => { controller.abort(); clearTimeout(timer) }
  }, [key, load, enabled, revision])
  const current = state.key === key ? state : { key, loading: true }
  return { ...current, refresh: () => setRevision(value => value + 1) }
}
