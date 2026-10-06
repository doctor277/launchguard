import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

const demoUrl = process.env.PAYMENT_URL ?? 'http://localhost:8081'
const keycloakUrl = process.env.KEYCLOAK_URL ?? 'http://localhost:8085'
const automationSecret = process.env.OIDC_AUTOMATION_CLIENT_SECRET ?? 'local-automation-secret-demo-only'

async function json<T>(response: Awaited<ReturnType<APIRequestContext['get']>>): Promise<T> {
  if (!response.ok()) {
    const body = await response.text()
    expect(response.ok(), `${response.url()} returned ${response.status()}: ${body}`).toBeTruthy()
  }
  return response.json() as Promise<T>
}

async function automationToken(request: APIRequestContext) {
  const response = await request.post(`${keycloakUrl}/realms/launchguard/protocol/openid-connect/token`, {
    form: {
      grant_type: 'client_credentials',
      client_id: 'launchguard-automation',
      client_secret: automationSecret,
    },
  })
  return (await json<{ access_token: string }>(response)).access_token
}

async function login(page: Page, username: string, password: string) {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Sign in to LaunchGuard' })).toBeVisible()
  await page.getByRole('button', { name: 'Sign in with OIDC' }).click()
  await expect(page).toHaveURL(/\/realms\/launchguard\/protocol\/openid-connect\/auth/)
  await page.getByLabel('Username or email').fill(username)
  await page.locator('#password').fill(password)
  await page.getByRole('button', { name: 'Sign In' }).click()
  await expect(page).toHaveURL(/#\/$/)
}

async function browserAccessToken(page: Page) {
  return page.evaluate(() => {
    const key = Object.keys(sessionStorage).find(candidate => candidate.startsWith('oidc.user:'))
    if (!key) throw new Error('OIDC session was not stored in sessionStorage.')
    const value = JSON.parse(sessionStorage.getItem(key) ?? '{}') as { access_token?: string }
    if (!value.access_token) throw new Error('OIDC session has no access token.')
    return value.access_token
  })
}

test('secured dashboard authenticates, enforces roles, and preserves monitoring behavior', async ({ page, request }) => {
  const fixtureName = `dashboard-smoke-${Date.now()}`
  const token = await automationToken(request)
  const headers = { Authorization: `Bearer ${token}` }
  let serviceId: string | undefined
  try {
    await expect((await request.post(`${demoUrl}/admin/recover`)).ok()).toBeTruthy()
    const service = await json<{ id: string }>(await request.post('/api/services', {
      headers,
      data: { name: fixtureName, baseUrl: 'http://payment-service:8081', healthPath: '/health' },
    }))
    serviceId = service.id
    await json(await request.post(`/api/services/${serviceId}/deployments`, {
      headers,
      data: { version: '1.0.0-smoke', commitSha: 'a921fc7', description: 'V1.0 authenticated dashboard smoke fixture', source: 'CI', environment: 'compose-smoke', imageTag: 'launchguard/payment-service:smoke', externalId: fixtureName },
    }))

    await page.goto('/')
    await expect(page.getByRole('heading', { name: 'Sign in to LaunchGuard' })).toBeVisible()
    expect((await request.get('/api/services')).status()).toBe(401)
    await login(page, 'launchguard-admin', 'admin-demo-only')
    await expect(page.getByText('Local Administrator')).toBeVisible()
    await expect(page.getByText(/ADMIN/)).toBeVisible()

    const row = page.getByRole('row').filter({ hasText: fixtureName })
    await expect(row).toBeVisible()
    await json(await request.post(`/api/services/${serviceId}/check`, { headers }))
    await expect.poll(async () => {
      const current = await json<{ status: string }>(await request.get(`/api/services/${serviceId}`, { headers }))
      return current.status
    }).toBe('HEALTHY')
    await page.getByRole('button', { name: 'Refresh now' }).first().click()
    await expect(row).toContainText('HEALTHY')
    await row.getByRole('link', { name: new RegExp(fixtureName) }).click()
    await expect(page.getByRole('heading', { name: fixtureName })).toBeVisible()
    await expect(page.getByText('1.0.0-smoke').first()).toBeVisible()
    await expect(page.getByRole('button', { name: 'Run health check' })).toBeVisible()

    await expect((await request.post(`${demoUrl}/admin/fail`)).ok()).toBeTruthy()
    for (let i = 0; i < 3; i++) await json(await request.post(`/api/services/${serviceId}/check`, { headers }))
    await page.getByRole('button', { name: 'Refresh now' }).first().click()
    await expect(page.getByRole('heading', { name: 'Open incident' })).toBeVisible()
    await expect(page.getByText(/Deployment 1.0.0-smoke/)).toBeVisible()

    await expect((await request.post(`${demoUrl}/admin/recover`)).ok()).toBeTruthy()
    for (let i = 0; i < 2; i++) await json(await request.post(`/api/services/${serviceId}/check`, { headers }))
    await page.getByRole('button', { name: 'Refresh now' }).first().click()
    await expect(page.getByText(/No open incident/)).toBeVisible()
    await expect(page.getByText('RESOLVED').first()).toBeVisible()

    await page.getByRole('button', { name: 'Sign out' }).click()
    await expect(page.getByRole('heading', { name: 'Sign in to LaunchGuard' })).toBeVisible()
    expect((await request.get('/api/services')).status()).toBe(401)

    await login(page, 'launchguard-viewer', 'viewer-demo-only')
    await expect(page.getByText('Local Viewer')).toBeVisible()
    await row.getByRole('link', { name: new RegExp(fixtureName) }).click()
    await expect(page.getByRole('button', { name: 'Run health check' })).toHaveCount(0)
    const viewerToken = await browserAccessToken(page)
    const forbidden = await request.post(`/api/services/${serviceId}/check`, {
      headers: { Authorization: `Bearer ${viewerToken}` },
    })
    expect(forbidden.status()).toBe(403)
  } finally {
    const recovery = await request.post(`${demoUrl}/admin/recover`)
    const deletion = serviceId ? await request.delete(`/api/services/${serviceId}`, { headers }) : undefined
    expect(recovery.ok(), `Demo recovery failed with HTTP ${recovery.status()}`).toBeTruthy()
    if (serviceId) {
      expect(deletion!.status(), `Fixture deletion failed: ${await deletion!.text()}`).toBe(204)
      expect((await request.get(`/api/services/${serviceId}`, { headers })).status()).toBe(404)
    }
  }
})
