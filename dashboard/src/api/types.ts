// Mirror the backend's API DTOs, not JPA entities. Timestamps are ISO-8601 instants.
export type ServiceStatus = 'HEALTHY' | 'DOWN' | 'UNKNOWN'
export type MetricsWindow = '1h' | '24h' | '7d' | '30d'
export interface CurrentDeployment { id: string; version: string; commitSha: string | null; deployedAt: string }
export interface Service {
  id: string; name: string; baseUrl: string; healthPath: string; status: ServiceStatus
  lastCheckedAt: string | null; createdAt: string; updatedAt: string
  currentDeployment: CurrentDeployment | null; hasOpenIncident: boolean
}
export interface Page<T> {
  content: T[]; page: number; size: number; totalElements: number; totalPages: number; first: boolean; last: boolean
}
export interface Reliability {
  totalChecks: number; healthyChecks: number; failedChecks: number; availabilityPercentage: number
  averageResponseTimeMs: number | null; minResponseTimeMs: number | null; maxResponseTimeMs: number | null
  lastCheckedAt: string | null
}
export interface ServiceMetrics extends Reliability { serviceId: string; status: ServiceStatus; lastFailureAt: string | null }
export interface TimelinePoint { timestamp: string; status: ServiceStatus; responseTimeMs: number }
export interface HealthCheck {
  id: string; serviceId: string; deploymentId: string | null; status: ServiceStatus
  httpStatus: number | null; responseTimeMs: number; errorMessage: string | null
  checkedAt: string; probeRequestId: string | null
}
export interface Deployment extends CurrentDeployment {
  serviceId: string; description: string | null; createdAt: string; current: boolean
  source: 'MANUAL' | 'CI'; environment: string | null; imageTag: string | null; externalId: string | null
}
export interface DeploymentMetrics extends Reliability {
  deploymentId: string; serviceId: string; version: string; commitSha: string | null
  current: boolean; deployedAt: string; firstFailureAt: string | null
}
export interface Incident {
  id: string; serviceId: string; serviceName: string; status: 'OPEN' | 'RESOLVED'
  deployment: { id: string; version: string; commitSha: string | null } | null
  triggerReason: string; startedAt: string; resolvedAt: string | null; durationSeconds: number | null
}
export interface IncidentMetrics {
  serviceId: string; window: string; totalIncidents: number; resolvedIncidents: number; openIncidents: number
  averageResolutionTimeSeconds: number | null; longestIncidentSeconds: number | null
}
export type Resource<T> = { data: T; error?: never } | { data?: never; error: string }
