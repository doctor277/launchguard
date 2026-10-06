export type LaunchGuardRole = 'VIEWER' | 'OPERATOR' | 'ADMIN'

export interface AuthConfig {
  issuer: string
  clientId: string
  audience: string
  scope: string
}

export interface AuthenticatedUser {
  subject: string
  displayName: string
  roles: ReadonlySet<LaunchGuardRole>
}

export interface AuthState {
  loading: boolean
  authenticated: boolean
  sessionExpired: boolean
  error: string | null
  user: AuthenticatedUser | null
  signIn: () => Promise<void>
  signOut: () => Promise<void>
}
