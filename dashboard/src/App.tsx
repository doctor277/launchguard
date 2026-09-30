import { useEffect, useState } from 'react'
import { Overview } from './features/overview'
import { ServiceDetail } from './features/ServiceDetail'
import { Notice } from './components/ui'

export function App() {
  const [hash, setHash] = useState(window.location.hash)
  useEffect(() => {
    const changed = () => { setHash(window.location.hash); window.scrollTo?.(0, 0) }
    window.addEventListener('hashchange', changed)
    return () => window.removeEventListener('hashchange', changed)
  }, [])
  const match = /^#\/services\/([0-9a-f-]{36})$/i.exec(hash)
  const overview = hash === '' || hash === '#' || hash === '#/'
  return <div className="app-shell"><a className="skip-link" href="#main" onClick={event => { event.preventDefault(); document.getElementById('main')?.focus() }}>Skip to content</a>
    <aside className="sidebar"><a className="brand" href="#/" aria-label="LaunchGuard overview"><span className="brand-mark" aria-hidden="true">L<span>G</span></span><span>LaunchGuard<small>Service operations</small></span></a><nav aria-label="Main navigation"><a href="#/" className={overview ? 'nav-active' : ''}><span className="nav-icon" aria-hidden="true">◫</span> Overview</a>{match ? <span className="nav-selected">Service detail</span> : null}</nav><div className="sidebar-footer"><span className="connection-dot" />Local monitoring lab<small>Application view · REST API</small></div></aside>
    <div className="main-shell"><header className="topbar"><span>Operations / {match ? 'Service detail' : 'Overview'}</span><span className="muted">LaunchGuard</span></header><main id="main" tabIndex={-1}>{overview ? <Overview /> : match ? <ServiceDetail key={match[1]} id={match[1]!} /> : <><h1>Page not found</h1><Notice>Choose a service from the <a href="#/">overview</a>.</Notice></>}</main><footer>Service reliability from PostgreSQL history. Platform telemetry remains in Grafana.</footer></div>
  </div>
}
