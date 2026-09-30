import type {
  Deployment, DeploymentMetrics, HealthCheck, Incident, IncidentMetrics, MetricsWindow,
  Page, Resource, Service, ServiceMetrics, TimelinePoint,
} from './types'

export class ApiError extends Error {
  constructor(message: string, readonly status: number) { super(message); this.name = 'ApiError' }
}

export async function get<T>(path: string, signal: AbortSignal): Promise<T> {
  const controller = new AbortController()
  const abort = () => controller.abort()
  signal.addEventListener('abort', abort, { once: true })
  if (signal.aborted) controller.abort()
  const timeout = setTimeout(abort, 10_000)
  try {
    const response = await fetch(`/api${path}`, { signal: controller.signal, headers: { Accept: 'application/json' }, cache: 'no-store' })
    if (!response.ok) {
      let message = `Request failed (HTTP ${response.status})`
      try {
        const body: unknown = await response.json()
        if (typeof body === 'object' && body !== null && 'message' in body && typeof body.message === 'string') message = body.message
      } catch { /* A proxy's HTML error is not a structured backend error. */ }
      throw new ApiError(message, response.status)
    }
    if (response.status === 204) return null as T
    return await response.json() as T
  } catch (error) {
    if (signal.aborted) throw error
    if (error instanceof ApiError) throw error
    throw new ApiError(controller.signal.aborted ? 'Request timed out after 10 seconds.' : 'Cannot reach the LaunchGuard API. Check the backend and proxy.', 0)
  } finally {
    clearTimeout(timeout)
    signal.removeEventListener('abort', abort)
  }
}

export function errorMessage(error: unknown) { return error instanceof Error ? error.message : 'Unexpected request failure.' }
export async function resource<T>(request: Promise<T>): Promise<Resource<T>> {
  try { return { data: await request } } catch (error) { return { error: errorMessage(error) } }
}

// A bounded fan-out, preserving input order. Never issue N simultaneous metrics requests.
export async function mapLimited<T, R>(items: T[], task: (item: T) => Promise<R>, signal: AbortSignal, limit = 4): Promise<R[]> {
  const results: R[] = new Array(items.length)
  let next = 0
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length && !signal.aborted) {
      const index = next++
      results[index] = await task(items[index]!)
    }
  }))
  if (signal.aborted) throw new DOMException('Request cancelled', 'AbortError')
  return results
}

const scope = (id: string) => `/services/${encodeURIComponent(id)}`
export async function loadOverview(signal: AbortSignal) {
  const services = await get<Service[]>('/services', signal)
  const measurements = await mapLimited(services, service => resource(get<ServiceMetrics>(`${scope(service.id)}/metrics?window=24h`, signal)), signal)
  const metrics = Object.fromEntries(services.map((service, index) => [service.id, measurements[index]!]))
  return { services, metrics }
}
export type OverviewData = Awaited<ReturnType<typeof loadOverview>>

export interface DetailSelection { window: MetricsWindow; checksPage: number; deploymentsPage: number; incidentsPage: number }
export async function loadDetail(id: string, selection: DetailSelection, signal: AbortSignal) {
  const base = scope(id)
  const service = await get<Service>(base, signal)
  // Individual history/metrics failures don't hide the service or other successful sections.
  const [metrics, timeline, checks, deployments] = await Promise.all([
    resource(get<ServiceMetrics>(`${base}/metrics?window=${selection.window}`, signal)),
    resource(get<TimelinePoint[]>(`${base}/metrics/timeline?window=${selection.window}`, signal)),
    resource(get<Page<HealthCheck>>(`${base}/checks?page=${selection.checksPage}&size=20`, signal)),
    resource(get<Page<Deployment>>(`${base}/deployments?page=${selection.deploymentsPage}&size=20`, signal)),
  ])
  const [incidents, currentIncident, incidentMetrics, currentDeployment] = await Promise.all([
    resource(get<Page<Incident>>(`${base}/incidents?page=${selection.incidentsPage}&size=20`, signal)),
    resource(get<Incident | null>(`${base}/incidents/current`, signal)),
    resource(get<IncidentMetrics>(`${base}/incident-metrics?window=${selection.window}`, signal)),
    service.currentDeployment ? resource(get<Deployment>(`${base}/deployments/${encodeURIComponent(service.currentDeployment.id)}`, signal)) : Promise.resolve({ data: null } as Resource<Deployment | null>),
  ])
  return { service, metrics, timeline, checks, deployments, incidents, currentIncident, incidentMetrics, currentDeployment }
}
export type DetailData = Awaited<ReturnType<typeof loadDetail>>
export function loadDeploymentMetrics(serviceId: string, deploymentId: string, signal: AbortSignal) {
  return get<DeploymentMetrics>(`${scope(serviceId)}/deployments/${encodeURIComponent(deploymentId)}/metrics`, signal)
}
