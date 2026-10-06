import { describe, expect, it } from 'vitest'
import { validateOidcIssuer } from './AuthContext'

describe('OIDC issuer transport validation', () => {
  it('accepts HTTPS and local HTTP issuers', () => {
    expect(validateOidcIssuer('https://id.example.com/realms/launchguard').origin).toBe('https://id.example.com')
    expect(validateOidcIssuer('http://localhost:8085/realms/launchguard').origin).toBe('http://localhost:8085')
    expect(validateOidcIssuer('http://127.0.0.1:8085/realms/launchguard').origin).toBe('http://127.0.0.1:8085')
  })

  it('rejects cleartext non-loopback and non-HTTP issuers', () => {
    expect(() => validateOidcIssuer('http://id.example.com/realms/launchguard')).toThrow('must use HTTPS')
    expect(() => validateOidcIssuer('file:///tmp/issuer')).toThrow('must use HTTP or HTTPS')
  })
})
