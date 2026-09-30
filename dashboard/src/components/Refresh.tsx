import { dateTime } from './ui'

export function Refresh({ loading, updatedAt, onRefresh }: { loading: boolean; updatedAt?: Date; onRefresh: () => void }) {
  return <div className="refresh-control"><span>{updatedAt ? `Updated ${dateTime(updatedAt.toISOString())}` : 'Awaiting first response'}<small>Refreshes every 10s while visible</small></span><button disabled={loading} onClick={onRefresh}>{loading ? 'Refreshing…' : 'Refresh now'}</button></div>
}
