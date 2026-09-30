import type { ReactNode } from 'react'
import type { Page, Resource, ServiceStatus } from '../api/types'

export function Status({ status }: { status: ServiceStatus | 'OPEN' | 'RESOLVED' }) {
  return <span className={`status status-${status.toLowerCase()}`}><span className="status-dot" aria-hidden="true" />{status}</span>
}
export function dateTime(value: string | null | undefined) {
  return value ? new Date(value).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'medium' }) : 'Not yet checked'
}
export function latency(value: number | null | undefined) { return value == null ? '—' : `${value.toLocaleString(undefined, { maximumFractionDigits: 2 })} ms` }
export function availability(totalChecks: number, percentage: number) { return totalChecks === 0 ? 'No checks' : `${percentage.toFixed(2)}%` }
export function Notice({ children, error = false }: { children: ReactNode; error?: boolean }) {
  return <div className={`notice ${error ? 'notice-error' : ''}`} role={error ? 'alert' : 'status'}>{children}</div>
}
export function Loading({ label = 'Loading LaunchGuard data…' }: { label?: string }) {
  return <div className="loading-state" role="status"><span className="loading-bar" />{label}</div>
}
export function Section({ title, children, action }: { title: string; children: ReactNode; action?: ReactNode }) {
  return <section className="section"><div className="section-heading"><h2>{title}</h2>{action}</div>{children}</section>
}
export function ResourceView<T>({ value, children }: { value: Resource<T>; children: (data: T) => ReactNode }) {
  return value.error !== undefined ? <Notice error>{value.error}</Notice> : <>{children(value.data)}</>
}
export function Pagination<T>({ data, onPage, label }: { data: Page<T>; onPage: (page: number) => void; label: string }) {
  return <nav className="pagination" aria-label={`${label} pagination`}>
    <span>{data.totalElements.toLocaleString()} records · Page {data.totalPages === 0 ? 0 : data.page + 1} of {data.totalPages}</span>
    <div><button disabled={data.first} onClick={() => onPage(Math.max(0, data.page - 1))}>Previous</button><button disabled={data.last} onClick={() => onPage(data.page + 1)}>Next</button></div>
  </nav>
}
