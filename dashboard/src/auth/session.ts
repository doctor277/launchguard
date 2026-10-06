let accessToken: string | null = null
let unauthorizedHandler: (() => void) | null = null

export function configureApiSession(token: string | null, onUnauthorized: (() => void) | null) {
  accessToken = token
  unauthorizedHandler = onUnauthorized
}

export function currentAccessToken() { return accessToken }

export function notifyUnauthorized() { unauthorizedHandler?.() }
