import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'
import { configureApiSession } from '../auth/session'

afterEach(() => { cleanup(); configureApiSession(null, null); vi.unstubAllGlobals(); vi.useRealTimers() })
