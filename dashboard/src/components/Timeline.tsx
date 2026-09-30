import type { TimelinePoint } from '../api/types'
import { dateTime, Notice } from './ui'

// Retain peaks and failures in time buckets instead of rendering thousands of SVG nodes.
export function chartPoints(points: TimelinePoint[], maximum = 240): TimelinePoint[] {
  if (points.length <= maximum) return points
  const result = [points[0]!]
  const bucket = Math.ceil((points.length - 2) / Math.floor((maximum - 2) / 2))
  for (let start = 1; start < points.length - 1; start += bucket) {
    const group = points.slice(start, Math.min(start + bucket, points.length - 1))
    const peak = group.reduce((best, point) => point.responseTimeMs > best.responseTimeMs ? point : best)
    const failures = group.filter(point => point.status === 'DOWN')
    const failure = failures.length ? failures.reduce((best, point) => point.responseTimeMs > best.responseTimeMs ? point : best) : null
    result.push(...group.filter(point => point === peak || point === failure))
  }
  result.push(points[points.length - 1]!)
  return result
}
export function Timeline({ points }: { points: TimelinePoint[] }) {
  if (points.length === 0) return <Notice>No checks in this time window.</Notice>
  const displayed = chartPoints(points)
  const start = new Date(points[0]!.timestamp).getTime()
  const end = new Date(points[points.length - 1]!.timestamp).getTime()
  const maximum = points.reduce((peak, point) => Math.max(peak, point.responseTimeMs), 1)
  const coordinate = (point: TimelinePoint) => ({ x: 58 + ((new Date(point.timestamp).getTime() - start) / Math.max(1, end - start)) * 932, y: 177 - (point.responseTimeMs / maximum) * 143 })
  return <figure className="timeline"><div className="chart-key"><span><i className="healthy-dot" />HEALTHY</span><span><i className="down-dot" />DOWN</span><span className="muted">Response time in milliseconds</span></div>
    <svg viewBox="0 0 1020 218" role="img" aria-label={`Latency timeline: ${points.length} checks, maximum ${maximum} milliseconds`}>
      {[0, .5, 1].map(ratio => <g key={ratio}><line x1="58" x2="990" y1={177 - ratio * 143} y2={177 - ratio * 143} className="chart-grid" /><text x="44" y={181 - ratio * 143} textAnchor="end">{Math.round(maximum * ratio)}</text></g>)}
      <polyline className="chart-line" points={displayed.map(point => { const { x, y } = coordinate(point); return `${x},${y}` }).join(' ')} />
      {displayed.map((point, index) => { const { x, y } = coordinate(point); return <circle key={`${point.timestamp}-${index}`} cx={x} cy={y} r={point.status === 'DOWN' ? 4 : 2.5} className={point.status === 'DOWN' ? 'chart-failure' : 'chart-success'}><title>{dateTime(point.timestamp)} · {point.status} · {point.responseTimeMs} ms</title></circle> })}
      <text x="58" y="207">{new Date(start).toLocaleString()}</text><text x="990" y="207" textAnchor="end">{new Date(end).toLocaleString()}</text>
    </svg><figcaption>{points.length.toLocaleString()} checks{displayed.length < points.length ? ` · ${displayed.length} representative points; buckets prioritize failures and latency peaks` : ''}. History below retains individual results.</figcaption>
  </figure>
}
