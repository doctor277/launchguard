import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthState, LaunchGuardRole } from './auth/types'
import { mockApi } from './test/fixtures'

const auth = vi.hoisted(() => ({ value: null as AuthState | null }))
vi.mock('./auth/AuthContext', () => ({
  useAuth: () => auth.value,
  Authorized: ({ roles, children }: { roles: LaunchGuardRole[]; children: ReactNode }) =>
    auth.value?.user && roles.some(role => auth.value!.user!.roles.has(role)) ? children : null,
}))

import { App } from './App'

function state(overrides: Partial<AuthState> = {}): AuthState {
  return {
    loading: false,
    authenticated: false,
    sessionExpired: false,
    error: null,
    user: null,
    signIn: vi.fn().mockResolvedValue(undefined),
    signOut: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  }
}

describe('dashboard authentication shell', () => {
  beforeEach(() => { window.location.hash = ''; auth.value = state() })

  it('requires authentication and starts the OIDC login interaction', async () => {
    render(<App />)
    expect(screen.getByRole('heading', { name: 'Sign in to LaunchGuard' })).toBeVisible()
    await userEvent.click(screen.getByRole('button', { name: 'Sign in with OIDC' }))
    expect(auth.value!.signIn).toHaveBeenCalledOnce()
  })

  it('shows an expired-session explanation', () => {
    auth.value = state({ sessionExpired: true })
    render(<App />)
    expect(screen.getByText(/session expired/i)).toBeVisible()
  })

  it('shows the authenticated identity and supports logout', async () => {
    mockApi()
    auth.value = state({
      authenticated: true,
      user: { subject: 'admin-1', displayName: 'Local Administrator', roles: new Set(['ADMIN']) },
    })
    render(<App />)
    expect(await screen.findByText('Local Administrator')).toBeVisible()
    expect(screen.getByText('ADMIN')).toBeVisible()
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }))
    expect(auth.value!.signOut).toHaveBeenCalledOnce()
  })
})
