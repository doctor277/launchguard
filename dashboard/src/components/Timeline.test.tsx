import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { chartPoints, Timeline } from './Timeline'
import type { TimelinePoint } from '../api/types'
import { instant } from '../test/fixtures'

describe('timeline', () => {
  it('shows an empty-window message', () => { render(<Timeline points={[]} />); expect(screen.getByText('No checks in this time window.')).toBeVisible() })
  it('shows timestamp, status and latency for actual observations', () => {
    render(<Timeline points={[{ timestamp: instant, status: 'DOWN', responseTimeMs: 2100 }]} />)
    expect(screen.getByRole('img')).toHaveAccessibleName('Latency timeline: 1 checks, maximum 2100 milliseconds')
    expect(screen.getByText(/DOWN · 2100 ms/)).toBeInTheDocument()
  })
  it('bounds chart nodes while preserving first/last, failures, and latency peaks', () => {
    const points: TimelinePoint[] = Array.from({ length: 10_000 }, (_, i) => ({ timestamp: new Date(i * 1000).toISOString(), status: i === 3 ? 'DOWN' : 'HEALTHY', responseTimeMs: i === 4 ? 8000 : 42 }))
    const sampled = chartPoints(points)
    expect(sampled.length).toBeLessThanOrEqual(240)
    expect(sampled[0]).toBe(points[0])
    expect(sampled.at(-1)).toBe(points.at(-1))
    expect(sampled).toContain(points[3])
    expect(sampled).toContain(points[4])
  })
})
