import { useCallback, useState } from 'react'
import { loadDeploymentMetrics, loadDetail } from '../api/client'
import type { Deployment, Incident, MetricsWindow, Reliability, Resource } from '../api/types'
import { usePolling } from '../hooks/usePolling'
import { Refresh } from '../components/Refresh'
import { Timeline } from '../components/Timeline'
import { availability, dateTime, latency, Loading, Notice, Pagination, ResourceView, Section, Status } from '../components/ui'

function ReliabilityStats({ metrics }: { metrics: Reliability }) {
  return <dl className="reliability-grid"><div><dt>Availability</dt><dd>{availability(metrics.totalChecks, metrics.availabilityPercentage)}</dd></div><div><dt>Total checks</dt><dd>{metrics.totalChecks.toLocaleString()}</dd></div><div><dt>Healthy / failed</dt><dd>{metrics.healthyChecks} / {metrics.failedChecks}</dd></div><div><dt>Avg. response</dt><dd>{latency(metrics.averageResponseTimeMs)}</dd></div><div><dt>Min. / max. response</dt><dd>{latency(metrics.minResponseTimeMs)} / {latency(metrics.maxResponseTimeMs)}</dd></div></dl>
}
function DeploymentInfo({ deployment }: { deployment: Deployment }) {
  return <div className="deployment-info"><div className="deployment-version"><strong>{deployment.version}</strong><span>{deployment.source} deployment · {dateTime(deployment.deployedAt)}</span></div><dl className="metadata"><div><dt>Commit SHA</dt><dd className="mono">{deployment.commitSha ?? 'Not provided'}</dd></div><div><dt>Environment</dt><dd>{deployment.environment ?? 'Not provided'}</dd></div><div><dt>Image</dt><dd className="mono">{deployment.imageTag ?? 'Not provided'}</dd></div><div><dt>Description</dt><dd>{deployment.description ?? 'Not provided'}</dd></div></dl></div>
}
function CurrentIncident({ value }: { value: Resource<Incident | null> }) {
  return <ResourceView value={value}>{incident => incident ? <div className="open-incident"><Status status="OPEN" /><div><h2>Open incident</h2><p>{incident.triggerReason}</p><span>Opened {dateTime(incident.startedAt)} · Deployment {incident.deployment?.version ?? 'not associated'}</span></div></div> : <Notice>No open incident. Resolved incidents remain in history below.</Notice>}</ResourceView>
}
function DeploymentReliability({ serviceId, deploymentId }: { serviceId: string; deploymentId: string }) {
  const loader = useCallback((signal: AbortSignal) => loadDeploymentMetrics(serviceId, deploymentId, signal), [serviceId, deploymentId])
  const poll = usePolling(`deployment:${serviceId}:${deploymentId}`, loader)
  return <div className="deployment-reliability"><h3>Deployment-specific reliability</h3>{poll.error ? <Notice error>{poll.error}</Notice> : null}{!poll.data && poll.loading ? <Loading label="Loading deployment metrics…" /> : null}{poll.data ? <><p className="muted">{poll.data.version} · All checks associated with this deployment, not the selected service time window.</p><ReliabilityStats metrics={poll.data} /><p className="muted">First failure: {poll.data.firstFailureAt ? dateTime(poll.data.firstFailureAt) : 'None recorded'}</p></> : null}</div>
}
export function ServiceDetail({ id }: { id: string }) {
  const [window, setWindow] = useState<MetricsWindow>('24h')
  const [checksPage, setChecksPage] = useState(0)
  const [deploymentsPage, setDeploymentsPage] = useState(0)
  const [incidentsPage, setIncidentsPage] = useState(0)
  const [selectedDeployment, setSelectedDeployment] = useState<string | null>(null)
  const loader = useCallback((signal: AbortSignal) => loadDetail(id, { window, checksPage, deploymentsPage, incidentsPage }, signal), [id, window, checksPage, deploymentsPage, incidentsPage])
  const poll = usePolling(`detail:${id}:${window}:${checksPage}:${deploymentsPage}:${incidentsPage}`, loader)
  const data = poll.data
  const deploymentId = selectedDeployment ?? data?.service.currentDeployment?.id
  return <>
    <a className="breadcrumb" href="#/">← All services</a>
    <div className="page-heading"><div><h1>{data?.service.name ?? 'Service detail'}</h1><p>{data ? `${data.service.baseUrl}${data.service.healthPath}` : 'Loading service information'}</p></div><Refresh loading={poll.loading} updatedAt={poll.updatedAt} onRefresh={poll.refresh} /></div>
    {poll.error ? <Notice error>{poll.error}{data ? ' Showing the last successful refresh; data may be stale.' : ''}</Notice> : null}
    {!data && poll.loading ? <Loading label="Loading service detail…" /> : null}
    {data ? <>
      <div className="service-context"><Status status={data.service.status} /><span>Last checked: {dateTime(data.service.lastCheckedAt)}</span><span className="mono">{data.service.id}</span></div>
      <CurrentIncident value={data.currentIncident} />
      <Section title="Reliability" action={<label className="window-picker">Time window<select value={window} onChange={event => setWindow(event.target.value as MetricsWindow)}><option value="1h">Last hour</option><option value="24h">Last 24 hours</option><option value="7d">Last 7 days</option><option value="30d">Last 30 days</option></select></label>}>
        <ResourceView value={data.metrics}>{metrics => <><ReliabilityStats metrics={metrics} /><p className="page-note">Last failure in this window: {metrics.lastFailureAt ? dateTime(metrics.lastFailureAt) : 'None recorded'}. Availability is check-based, not time-based uptime.</p></>}</ResourceView>
        <ResourceView value={data.timeline}>{points => <Timeline points={points} />}</ResourceView>
      </Section>
      <Section title="Recent health checks"><ResourceView value={data.checks}>{checks => <>{checks.content.length === 0 ? <Notice>No checks on this page.</Notice> : <div className="table-scroll"><table><thead><tr><th>Checked at</th><th>Status</th><th>Response</th><th>HTTP</th><th>Deployment / error</th></tr></thead><tbody>{checks.content.map(check => <tr key={check.id}><td className="date-cell">{dateTime(check.checkedAt)}</td><td><Status status={check.status} /></td><td>{latency(check.responseTimeMs)}</td><td>{check.httpStatus ?? 'No response'}</td><td><small className="mono">{check.deploymentId ?? 'No deployment'}</small>{check.errorMessage ? <small className="request-error">{check.errorMessage}</small> : null}</td></tr>)}</tbody></table></div>}<Pagination data={checks} onPage={setChecksPage} label="Health checks" /></>}</ResourceView></Section>
      <Section title="Current deployment"><ResourceView value={data.currentDeployment}>{deployment => deployment ? <DeploymentInfo deployment={deployment} /> : <Notice>No deployment registered for this service.</Notice>}</ResourceView></Section>
      <Section title="Deployment history"><ResourceView value={data.deployments}>{deployments => <>{deployments.content.length === 0 ? <Notice>No deployments on this page.</Notice> : <div className="table-scroll"><table><thead><tr><th>Version</th><th>Deployed at</th><th>Commit</th><th>Environment / source</th><th>Image</th><th>Reliability</th></tr></thead><tbody>{deployments.content.map(deployment => <tr key={deployment.id}><td><strong className="version">{deployment.version}</strong>{deployment.current ? <small className="current-label">Current</small> : null}</td><td className="date-cell">{dateTime(deployment.deployedAt)}</td><td className="mono" title={deployment.commitSha ?? undefined}>{deployment.commitSha?.slice(0, 7) ?? 'Not provided'}</td><td>{deployment.environment ?? 'Not provided'}<small>{deployment.source}</small></td><td className="wrap mono">{deployment.imageTag ?? 'Not provided'}</td><td><button className="text-button" aria-pressed={deploymentId === deployment.id} onClick={() => setSelectedDeployment(deployment.id)}>View metrics</button></td></tr>)}</tbody></table></div>}<Pagination data={deployments} onPage={setDeploymentsPage} label="Deployments" /></>}</ResourceView>{deploymentId ? <DeploymentReliability serviceId={id} deploymentId={deploymentId} /> : null}</Section>
      <Section title="Incident history" action={<span className="muted">Newest first · All recorded incidents</span>}>
        <ResourceView value={data.incidentMetrics}>{metrics => <p className="incident-summary">Opened in the selected window: <strong>{metrics.totalIncidents}</strong> · Open: <strong>{metrics.openIncidents}</strong> · Resolved: <strong>{metrics.resolvedIncidents}</strong>{metrics.averageResolutionTimeSeconds !== null ? ` · Avg. resolution: ${metrics.averageResolutionTimeSeconds}s` : ''}</p>}</ResourceView>
        <ResourceView value={data.incidents}>{incidents => <>{incidents.content.length === 0 ? <Notice>No incidents on this page.</Notice> : <div className="table-scroll"><table><thead><tr><th>State</th><th>Opened</th><th>Resolved</th><th>Duration</th><th>Deployment</th><th>Reason</th></tr></thead><tbody>{incidents.content.map(incident => <tr key={incident.id}><td><Status status={incident.status} /></td><td className="date-cell">{dateTime(incident.startedAt)}</td><td className="date-cell">{incident.resolvedAt ? dateTime(incident.resolvedAt) : 'Ongoing'}</td><td>{incident.durationSeconds === null ? 'Ongoing' : `${incident.durationSeconds}s`}</td><td>{incident.deployment?.version ?? 'Not associated'}</td><td className="wrap">{incident.triggerReason}</td></tr>)}</tbody></table></div>}<Pagination data={incidents} onPage={setIncidentsPage} label="Incidents" /></>}</ResourceView>
      </Section>
    </> : null}
  </>
}
