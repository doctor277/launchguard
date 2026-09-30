import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Overview, summarize } from './overview'
import { json, metrics, mockApi, service, SERVICE_ID } from '../test/fixtures'
import { Status } from '../components/ui'

describe('overview', () => {
  it.each(['HEALTHY', 'DOWN', 'UNKNOWN'] as const)('renders an explicit %s status', status => {
    render(<Status status={status} />)
    expect(screen.getByText(status)).toHaveClass(`status-${status.toLowerCase()}`)
  })
  it('derives summary counts including UNKNOWN and incidents during recovery', () => {
    expect(summarize([service(), service({ id: '2', status: 'DOWN', hasOpenIncident: true }), service({ id: '3', status: 'UNKNOWN', hasOpenIncident: false })])).toEqual({ total: 3, healthy: 1, down: 1, unknown: 1, open: 2 })
    expect(summarize([])).toEqual({ total: 0, healthy: 0, down: 0, unknown: 0, open: 0 })
  })
  it('shows loading while the HTTP request is outstanding', () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
    render(<Overview />)
    expect(screen.getByRole('status')).toHaveTextContent('Loading LaunchGuard data')
    expect(screen.queryByLabelText('Service summary')).not.toBeInTheDocument()
  })
  it('shows an actionable empty state instead of fabricated services', async () => {
    mockApi({ '/api/services': [] })
    render(<Overview />)
    expect(await screen.findByText('No services registered')).toBeVisible()
    expect(screen.getByText('./scripts/register-demo-services.ps1')).toBeVisible()
  })
  it('shows backend unavailability without a blank screen', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Network failure')))
    render(<Overview />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the LaunchGuard API')
  })
  it('renders actual reliability and deployment fields with a 24h query', async () => {
    const fetch = mockApi()
    render(<Overview />)
    expect(await screen.findByRole('link', { name: /payment-service/ })).toHaveAttribute('href', `#/services/${SERVICE_ID}`)
    expect(screen.getByText('80.00%')).toBeVisible()
    expect(screen.getByText('42.5 ms')).toBeVisible()
    expect(screen.getByText('1.2.0')).toBeVisible()
    expect(screen.getByText('abcdef1')).toBeVisible()
    expect(fetch.mock.calls.map(call => call[0])).toContain(`/api/services/${SERVICE_ID}/metrics?window=24h`)
  })
  it('renders no-check history without claiming 0% or 100% availability', async () => {
    mockApi({ [`/api/services/${SERVICE_ID}/metrics`]: metrics({ totalChecks: 0, healthyChecks: 0, failedChecks: 0, availabilityPercentage: 0, averageResponseTimeMs: null }) })
    render(<Overview />)
    expect(await screen.findByText('No checks')).toBeVisible()
    expect(screen.queryByText('0.00%')).not.toBeInTheDocument()
  })
  it('isolates a metrics failure from the registry and summary', async () => {
    mockApi({ [`/api/services/${SERVICE_ID}/metrics`]: json({ message: 'Metrics unavailable' }, 503) })
    render(<Overview />)
    expect(await screen.findByText('Unavailable')).toHaveAttribute('title', 'Metrics unavailable')
    expect(screen.getByRole('link', { name: /payment-service/ })).toBeVisible()
    expect(within(screen.getByLabelText('Service summary')).getByText('Monitored services')).toBeVisible()
  })
  it('filters by service name and status', async () => {
    mockApi()
    render(<Overview />)
    await screen.findByRole('link', { name: /payment-service/ })
    const user = userEvent.setup()
    await user.selectOptions(screen.getByLabelText('Status'), 'UNKNOWN')
    expect(screen.getByText('No services match these filters.')).toBeVisible()
    await user.selectOptions(screen.getByLabelText('Status'), 'ALL')
    await user.type(screen.getByPlaceholderText('Search service name'), 'order')
    expect(screen.getByText('No services match these filters.')).toBeVisible()
  })
})
