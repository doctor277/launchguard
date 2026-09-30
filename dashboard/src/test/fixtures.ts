import { vi } from 'vitest'
import type { Deployment, HealthCheck, Incident, Page, Service, ServiceMetrics } from '../api/types'

export const SERVICE_ID = '11111111-1111-4111-8111-111111111111'
export const DEPLOYMENT_ID = '22222222-2222-4222-8222-222222222222'
export const instant = '2026-09-29T01:00:00Z'
export const service = (overrides: Partial<Service> = {}): Service => ({ id: SERVICE_ID, name: 'payment-service', baseUrl: 'http://payment-service:8081', healthPath: '/health', status: 'HEALTHY', lastCheckedAt: instant, createdAt: instant, updatedAt: instant, currentDeployment: { id: DEPLOYMENT_ID, version: '1.2.0', commitSha: 'abcdef1234567', deployedAt: instant }, hasOpenIncident: true, ...overrides })
export const metrics = (overrides: Partial<ServiceMetrics> = {}): ServiceMetrics => ({ serviceId: SERVICE_ID, status: 'HEALTHY', totalChecks: 10, healthyChecks: 8, failedChecks: 2, availabilityPercentage: 80, averageResponseTimeMs: 42.5, minResponseTimeMs: 12, maxResponseTimeMs: 100, lastFailureAt: instant, lastCheckedAt: instant, ...overrides })
export const deployment = (overrides: Partial<Deployment> = {}): Deployment => ({ id: DEPLOYMENT_ID, serviceId: SERVICE_ID, version: '1.2.0', commitSha: 'abcdef1234567', deployedAt: instant, createdAt: instant, current: true, source: 'CI', environment: 'local', imageTag: 'launchguard/payment-service:sha-abcdef1', externalId: 'test-run-1', description: 'Payment release', ...overrides })
export const incident = (overrides: Partial<Incident> = {}): Incident => ({ id: '33333333-3333-4333-8333-333333333333', serviceId: SERVICE_ID, serviceName: 'payment-service', status: 'OPEN', deployment: { id: DEPLOYMENT_ID, version: '1.2.0', commitSha: 'abcdef1234567' }, triggerReason: '3 consecutive failed checks', startedAt: instant, resolvedAt: null, durationSeconds: null, ...overrides })
export const check: HealthCheck = { id: 'check-1', serviceId: SERVICE_ID, deploymentId: DEPLOYMENT_ID, status: 'DOWN', httpStatus: 500, responseTimeMs: 100, errorMessage: 'HTTP 500', checkedAt: instant, probeRequestId: 'request-1' }
export function page<T>(content: T[], overrides: Partial<Page<T>> = {}): Page<T> { return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0, first: true, last: true, ...overrides } }
export function json(body: unknown, status = 200) { return new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }) }
export function mockApi(overrides: Record<string, unknown | Response> = {}) {
  const routes: Record<string, unknown | Response> = {
    '/api/services': [service()],
    [`/api/services/${SERVICE_ID}`]: service(),
    [`/api/services/${SERVICE_ID}/metrics`]: metrics(),
    [`/api/services/${SERVICE_ID}/metrics/timeline`]: [{ timestamp: instant, status: 'DOWN', responseTimeMs: 100 }],
    [`/api/services/${SERVICE_ID}/checks`]: page([check]),
    [`/api/services/${SERVICE_ID}/deployments`]: page([deployment()]),
    [`/api/services/${SERVICE_ID}/deployments/${DEPLOYMENT_ID}`]: deployment(),
    [`/api/services/${SERVICE_ID}/deployments/${DEPLOYMENT_ID}/metrics`]: { ...metrics(), deploymentId: DEPLOYMENT_ID, version: '1.2.0', commitSha: 'abcdef1234567', current: true, deployedAt: instant, firstFailureAt: instant },
    [`/api/services/${SERVICE_ID}/incidents`]: page([incident(), incident({ id: 'resolved-1', status: 'RESOLVED', resolvedAt: instant, durationSeconds: 32 })]),
    [`/api/services/${SERVICE_ID}/incidents/current`]: incident(),
    [`/api/services/${SERVICE_ID}/incident-metrics`]: { serviceId: SERVICE_ID, window: '24h', totalIncidents: 2, resolvedIncidents: 1, openIncidents: 1, averageResolutionTimeSeconds: 32, longestIncidentSeconds: 32 },
    ...overrides,
  }
  const mock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    const path = String(input)
    const body = Object.hasOwn(routes, path) ? routes[path] : routes[path.split('?')[0]!]
    if (body === undefined) return json({ message: `Unexpected test request: ${path}` }, 404)
    return body instanceof Response ? body.clone() : json(body)
  })
  vi.stubGlobal('fetch', mock)
  return mock
}
