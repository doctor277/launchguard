import { useState } from 'react'
import { loadOverview } from '../api/client'
import type { Service, ServiceStatus } from '../api/types'
import { usePolling } from '../hooks/usePolling'
import { Refresh } from '../components/Refresh'
import { availability, dateTime, latency, Loading, Notice, Section, Status } from '../components/ui'

export function summarize(services: Service[]) {
  return {
    total: services.length,
    healthy: services.filter(service => service.status === 'HEALTHY').length,
    down: services.filter(service => service.status === 'DOWN').length,
    unknown: services.filter(service => service.status === 'UNKNOWN').length,
    open: services.filter(service => service.hasOpenIncident).length,
  }
}
export function Overview() {
  const poll = usePolling('overview', loadOverview)
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState<ServiceStatus | 'ALL'>('ALL')
  const data = poll.data
  const summary = data ? summarize(data.services) : null
  const filtered = data?.services.filter(service => service.name.toLowerCase().includes(search.toLowerCase()) && (status === 'ALL' || service.status === status))
  return <>
    <div className="page-heading"><div><h1>Service overview</h1><p>Health, reliability, and deployment context in one place.</p></div><Refresh loading={poll.loading} updatedAt={poll.updatedAt} onRefresh={poll.refresh} /></div>
    {poll.error ? <Notice error>{poll.error}{data ? ' Showing the last successful refresh; data may be stale.' : ''}</Notice> : null}
    {!data && poll.loading ? <Loading /> : null}
    {data && summary ? <>
      <div className="summary-band" aria-label="Service summary">
        {([['Monitored services', summary.total, ''], ['Healthy', summary.healthy, 'healthy'], ['Down', summary.down, 'down'], ['Open incidents', summary.open, 'open']] as const).map(([label, value, tone]) => <div key={label} className={`summary-item ${tone}`}><span>{label}</span><strong>{value}</strong><small>{label === 'Monitored services' ? `${summary.unknown} awaiting a first check` : label === 'Open incidents' ? 'Services with a confirmed incident' : 'Current service status'}</small></div>)}
      </div>
      <Section title="Monitored services" action={<span className="muted">Reliability over the last 24 hours</span>}>
        <div className="table-toolbar"><label className="search-label">Find a service<input type="search" placeholder="Search service name" value={search} onChange={event => setSearch(event.target.value)} /></label><label>Status<select value={status} onChange={event => setStatus(event.target.value as typeof status)}><option value="ALL">All statuses</option><option>HEALTHY</option><option>DOWN</option><option>UNKNOWN</option></select></label><span className="muted">{filtered?.length} of {summary.total} services</span></div>
        {data.services.length === 0 ? <div className="empty-state"><h3>No services registered</h3><p>Register your demo services to start monitoring.</p><code>./scripts/register-demo-services.ps1</code></div> : filtered?.length === 0 ? <Notice>No services match these filters.</Notice> : <div className="table-scroll"><table><thead><tr><th>Service</th><th>Status</th><th>Availability</th><th>Avg. response</th><th>Deployment</th><th>Last checked</th><th>Incident</th></tr></thead><tbody>{filtered?.map(service => {
          const measurement = data.metrics[service.id]
          return <tr key={service.id}><td><a className="service-link" href={`#/services/${service.id}`}>{service.name}<span aria-hidden="true">↗</span></a><small className="target-url">{service.baseUrl}{service.healthPath}</small></td><td><Status status={service.status} /></td><td>{measurement?.data ? availability(measurement.data.totalChecks, measurement.data.availabilityPercentage) : <span className="request-error" title={measurement?.error}>Unavailable</span>}</td><td>{measurement?.data ? latency(measurement.data.averageResponseTimeMs) : '—'}</td><td>{service.currentDeployment ? <><strong className="version">{service.currentDeployment.version}</strong><small className="mono">{service.currentDeployment.commitSha?.slice(0, 7) ?? 'No commit SHA'}</small></> : <span className="muted">No deployment</span>}</td><td className="date-cell">{dateTime(service.lastCheckedAt)}</td><td>{service.hasOpenIncident ? <span className="incident-indicator">OPEN</span> : <span className="muted">None</span>}</td></tr>
        })}</tbody></table></div>}
      </Section>
      <div className="page-note"><span>Availability = successful checks / total checks.</span><span>Incidents require consecutive failures; a single DOWN result may not open one.</span></div>
    </> : null}
  </>
}
