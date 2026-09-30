import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ServiceDetail } from './ServiceDetail'
import { DEPLOYMENT_ID, deployment, json, mockApi, page, service, SERVICE_ID } from '../test/fixtures'

describe('service detail', () => {
  it('renders service fields, reliability, checks, and a real-data chart', async () => {
    mockApi()
    render(<ServiceDetail id={SERVICE_ID} />)
    expect(await screen.findByRole('heading', { name: 'payment-service' })).toBeVisible()
    expect(screen.getByText('80.00%')).toBeVisible()
    expect(screen.getAllByText('42.5 ms').length).toBeGreaterThan(0)
    expect(screen.getByText('12 ms / 100 ms')).toBeVisible()
    expect(screen.getByRole('img', { name: /Latency timeline: 1 checks/ })).toBeVisible()
    expect(screen.getByText('HTTP 500')).toBeVisible()
  })
  it('renders the open incident, associated deployment, and resolved history', async () => {
    mockApi()
    render(<ServiceDetail id={SERVICE_ID} />)
    expect(await screen.findByRole('heading', { name: 'Open incident' })).toBeVisible()
    expect(screen.getByText(/Deployment 1.2.0/)).toBeVisible()
    expect(screen.getByText('RESOLVED')).toBeVisible()
    expect(screen.getByText('32s')).toBeVisible()
  })
  it('handles the actual 204 current-incident contract', async () => {
    mockApi({ [`/api/services/${SERVICE_ID}/incidents/current`]: json(null, 204) })
    render(<ServiceDetail id={SERVICE_ID} />)
    expect(await screen.findByText(/No open incident/)).toBeVisible()
  })
  it('shows full current deployment metadata and deployment-scoped reliability', async () => {
    const fetch = mockApi()
    render(<ServiceDetail id={SERVICE_ID} />)
    expect(await screen.findByText('Payment release')).toBeVisible()
    expect(screen.getAllByText('launchguard/payment-service:sha-abcdef1')).toHaveLength(2)
    expect(screen.getByText('abcdef1234567')).toBeVisible()
    expect(screen.getAllByText('local')).toHaveLength(2)
    await waitFor(() => expect(fetch.mock.calls.map(call => call[0])).toContain(`/api/services/${SERVICE_ID}/deployments/${DEPLOYMENT_ID}/metrics`))
    expect(await screen.findByText(/All checks associated with this deployment/)).toBeVisible()
  })
  it('selects a historical deployment without changing the backend current deployment', async () => {
    const older = deployment({ id: '44444444-4444-4444-8444-444444444444', version: '1.1.0', current: false })
    const fetch = mockApi({ [`/api/services/${SERVICE_ID}/deployments`]: page([older]), [`/api/services/${SERVICE_ID}/deployments/${older.id}/metrics`]: { ...older, totalChecks: 0, healthyChecks: 0, failedChecks: 0, availabilityPercentage: 0, averageResponseTimeMs: null, minResponseTimeMs: null, maxResponseTimeMs: null, firstFailureAt: null, lastCheckedAt: null } })
    render(<ServiceDetail id={SERVICE_ID} />)
    await screen.findByText('1.1.0')
    await userEvent.setup().click(screen.getByRole('button', { name: 'View metrics' }))
    expect(await screen.findByText(/1.1.0 · All checks/)).toBeVisible()
    expect(fetch.mock.calls.every(call => !call[1]?.method || call[1].method === 'GET')).toBe(true)
  })
  it('keeps successful sections visible when a history request fails', async () => {
    mockApi({ [`/api/services/${SERVICE_ID}/checks`]: json({ message: 'Check history unavailable' }, 503) })
    render(<ServiceDetail id={SERVICE_ID} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Check history unavailable')
    expect(screen.getByRole('heading', { name: 'Current deployment' })).toBeVisible()
  })
  it('uses backend pagination metadata and requests the next health-check page', async () => {
    const fetch = mockApi({ [`/api/services/${SERVICE_ID}/checks`]: page([], { totalElements: 25, totalPages: 2, last: false }) })
    render(<ServiceDetail id={SERVICE_ID} />)
    const nav = await screen.findByRole('navigation', { name: 'Health checks pagination' })
    expect(within(nav).getByText('25 records · Page 1 of 2')).toBeVisible()
    await userEvent.setup().click(within(nav).getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(fetch.mock.calls.map(call => call[0])).toContain(`/api/services/${SERVICE_ID}/checks?page=1&size=20`))
  })
  it('applies the selected window to metrics, timeline, and incident metrics only', async () => {
    const fetch = mockApi()
    render(<ServiceDetail id={SERVICE_ID} />)
    await screen.findByRole('heading', { name: 'payment-service' })
    await userEvent.setup().selectOptions(screen.getByLabelText('Time window'), '1h')
    await waitFor(() => expect(fetch.mock.calls.map(call => call[0])).toContain(`/api/services/${SERVICE_ID}/incident-metrics?window=1h`))
    expect(fetch.mock.calls.map(call => call[0])).toContain(`/api/services/${SERVICE_ID}/metrics/timeline?window=1h`)
  })
  it('handles UNKNOWN with no deployments or incidents', async () => {
    mockApi({ [`/api/services/${SERVICE_ID}`]: service({ status: 'UNKNOWN', lastCheckedAt: null, hasOpenIncident: false, currentDeployment: null }), [`/api/services/${SERVICE_ID}/incidents/current`]: json(null, 204), [`/api/services/${SERVICE_ID}/incidents`]: page([]), [`/api/services/${SERVICE_ID}/deployments`]: page([]) })
    render(<ServiceDetail id={SERVICE_ID} />)
    expect(await screen.findByText('UNKNOWN')).toBeVisible()
    expect(screen.getByText('No deployment registered for this service.')).toBeVisible()
  })
  it('renders a missing-service error instead of silently returning to the list', async () => {
    mockApi({ [`/api/services/${SERVICE_ID}`]: json({ message: 'Service not found' }, 404) })
    render(<ServiceDetail id={SERVICE_ID} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Service not found')
  })
})
