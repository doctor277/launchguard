import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { User, UserManager, WebStorageStateStore } from 'oidc-client-ts'
import { configureApiSession } from './session'
import type { AuthConfig, AuthState, AuthenticatedUser, LaunchGuardRole } from './types'

const applicationRoles = new Set<LaunchGuardRole>(['VIEWER', 'OPERATOR', 'ADMIN'])
const unauthenticated: AuthState = {
  loading: false,
  authenticated: false,
  sessionExpired: false,
  error: null,
  user: null,
  signIn: async () => {},
  signOut: async () => {},
}
export const AuthContext = createContext<AuthState>(unauthenticated)

export function validateOidcIssuer(value: string): URL {
  const issuer = new URL(value)
  if (!['http:', 'https:'].includes(issuer.protocol)) throw new Error('OIDC issuer must use HTTP or HTTPS.')
  const loopback = issuer.hostname === 'localhost' || issuer.hostname === '127.0.0.1' || issuer.hostname === '[::1]'
  if (issuer.protocol === 'http:' && !loopback) throw new Error('OIDC issuer must use HTTPS unless it targets loopback.')
  return issuer
}

async function loadConfiguration(): Promise<AuthConfig> {
  const response = await fetch('/auth-config.json', { cache: 'no-store', headers: { Accept: 'application/json' } })
  if (!response.ok) throw new Error(`Authentication configuration failed to load (HTTP ${response.status}).`)
  const value = await response.json() as Partial<AuthConfig>
  if (!value.issuer || !value.clientId) throw new Error('Authentication configuration is incomplete.')
  const issuer = validateOidcIssuer(value.issuer)
  return {
    issuer: issuer.toString().replace(/\/$/, ''),
    clientId: value.clientId,
    audience: value.audience ?? 'launchguard-api',
    scope: value.scope ?? 'openid profile email',
  }
}

function rolesFrom(user: User): ReadonlySet<LaunchGuardRole> {
  const raw = user.profile.roles
  const values = Array.isArray(raw) ? raw : typeof raw === 'string' ? raw.split(/[ ,]/) : []
  return new Set(values.map(value => String(value).toUpperCase()).filter((value): value is LaunchGuardRole => applicationRoles.has(value as LaunchGuardRole)))
}

function identity(user: User): AuthenticatedUser {
  const displayName = String(user.profile.name ?? user.profile.preferred_username ?? user.profile.email ?? user.profile.sub)
  return { subject: user.profile.sub, displayName, roles: rolesFrom(user) }
}

function createManager(config: AuthConfig) {
  const storage = new WebStorageStateStore({ store: window.sessionStorage })
  return new UserManager({
    authority: config.issuer,
    client_id: config.clientId,
    redirect_uri: `${window.location.origin}/auth/callback`,
    post_logout_redirect_uri: `${window.location.origin}/`,
    response_type: 'code',
    scope: config.scope,
    userStore: storage,
    stateStore: storage,
    loadUserInfo: false,
    monitorSession: false,
  })
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [manager, setManager] = useState<UserManager | null>(null)
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(true)
  const [sessionExpired, setSessionExpired] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const expire = useCallback(() => {
    configureApiSession(null, null)
    setUser(null)
    setSessionExpired(true)
  }, [])

  useEffect(() => {
    let active = true
    let configuredManager: UserManager | null = null
    void loadConfiguration().then(async config => {
      configuredManager = createManager(config)
      configuredManager.events.addAccessTokenExpired(expire)
      let current: User | null
      const callback = window.location.pathname === '/auth/callback'
        && (new URLSearchParams(window.location.search).has('code') || new URLSearchParams(window.location.search).has('error'))
      if (callback) {
        current = await configuredManager.signinRedirectCallback()
        window.history.replaceState({}, document.title, '/#/')
      } else {
        current = await configuredManager.getUser()
      }
      if (!active) return
      if (current?.expired) {
        await configuredManager.removeUser()
        current = null
        setSessionExpired(true)
      }
      setManager(configuredManager)
      setUser(current)
      configureApiSession(current?.access_token ?? null, expire)
    }).catch(reason => {
      if (active) setError(reason instanceof Error ? reason.message : 'Authentication initialization failed.')
    }).finally(() => { if (active) setLoading(false) })
    return () => {
      active = false
      configuredManager?.events.removeAccessTokenExpired(expire)
      configureApiSession(null, null)
    }
  }, [expire])

  const signIn = useCallback(async () => {
    if (!manager) throw new Error('Authentication is not ready.')
    setError(null)
    setSessionExpired(false)
    await manager.signinRedirect({ state: { returnUrl: window.location.href } })
  }, [manager])

  const signOut = useCallback(async () => {
    if (!manager) return
    configureApiSession(null, null)
    await manager.signoutRedirect()
  }, [manager])

  const value = useMemo<AuthState>(() => ({
    loading,
    authenticated: Boolean(user && !user.expired),
    sessionExpired,
    error,
    user: user ? identity(user) : null,
    signIn,
    signOut,
  }), [error, loading, sessionExpired, signIn, signOut, user])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  return useContext(AuthContext)
}

export function Authorized({ roles, children }: { roles: LaunchGuardRole[]; children: ReactNode }) {
  const auth = useAuth()
  return auth.user && roles.some(role => auth.user!.roles.has(role)) ? children : null
}
