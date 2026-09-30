import { expect, test, type APIRequestContext } from '@playwright/test'

const demoUrl = process.env.PAYMENT_URL ?? 'http://localhost:8081'

async function json<T>(response: Awaited<ReturnType<APIRequestContext['get']>>): Promise<T> {
  if (!response.ok()) {
    const body = await response.text()
    expect(response.ok(), `${response.url()} returned ${response.status()}: ${body}`).toBeTruthy()
  }
  return response.json() as Promise<T>
}

test('dashboard reflects real health, incidents, recovery, and deployment metadata', async ({ page, request }) => {
  const fixtureName = `dashboard-smoke-${Date.now()}`
  let serviceId: string | undefined
  try {
    await expect((await request.post(`${demoUrl}/admin/recover`)).ok()).toBeTruthy()
    const service = await json<{ id: string }>(await request.post('/api/services', { data: { name: fixtureName, baseUrl: 'http://payment-service:8081', healthPath: '/health' } }))
    serviceId = service.id
    await json(await request.post(`/api/services/${serviceId}/deployments`, { data: { version: '0.9.0-smoke', commitSha: 'a921fc7', description: 'V0.9 real dashboard smoke fixture', source: 'CI', environment: 'compose-smoke', imageTag: 'launchguard/payment-service:smoke', externalId: fixtureName } }))

    await page.goto('/')
    await expect(page).toHaveTitle(/LaunchGuard/)
    const row = page.getByRole('row').filter({ hasText: fixtureName })
    await expect(row).toContainText('UNKNOWN')

    await json(await request.post(`/api/services/${serviceId}/check`))
    await page.getByRole('button', { name: 'Refresh now' }).first().click()
    await expect(row).toContainText('HEALTHY')
    await row.getByRole('link', { name: new RegExp(fixtureName) }).click()
    await expect(page.getByRole('heading', { name: fixtureName })).toBeVisible()
    await expect(page.getByText('0.9.0-smoke').first()).toBeVisible()
    await expect(page.getByText('compose-smoke').first()).toBeVisible()
    await expect(page.getByText('launchguard/payment-service:smoke').first()).toBeVisible()

    await expect((await request.post(`${demoUrl}/admin/fail`)).ok()).toBeTruthy()
    for (let i = 0; i < 3; i++) await json(await request.post(`/api/services/${serviceId}/check`))
    await page.getByRole('button', { name: 'Refresh now' }).first().click()
    await expect(page.getByRole('heading', { name: 'Open incident' })).toBeVisible()
    await expect(page.getByText('DOWN').first()).toBeVisible()
    await expect(page.getByText(/Deployment 0.9.0-smoke/)).toBeVisible()

    await expect((await request.post(`${demoUrl}/admin/recover`)).ok()).toBeTruthy()
    for (let i = 0; i < 2; i++) await json(await request.post(`/api/services/${serviceId}/check`))
    await page.getByRole('button', { name: 'Refresh now' }).first().click()
    await expect(page.getByText(/No open incident/)).toBeVisible()
    await expect(page.getByText('RESOLVED').first()).toBeVisible()
    await expect(page.getByText('HEALTHY').first()).toBeVisible()
  } finally {
    const recovery = await request.post(`${demoUrl}/admin/recover`)
    const deletion = serviceId ? await request.delete(`/api/services/${serviceId}`) : undefined
    expect(recovery.ok(), `Demo recovery failed with HTTP ${recovery.status()}`).toBeTruthy()
    if (serviceId) {
      expect(deletion!.status(), `Fixture deletion failed: ${await deletion!.text()}`).toBe(204)
      expect((await request.get(`/api/services/${serviceId}`)).status()).toBe(404)
    }
  }
})
