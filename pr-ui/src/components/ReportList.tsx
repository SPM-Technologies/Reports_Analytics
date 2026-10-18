import type { Dashboard, SavedReport } from '../types';

interface Props {
  reports: SavedReport[];
  dashboards: Dashboard[];
  currentId: number | null;
  currentDashboardId: number | null;
  onOpen: (report: SavedReport) => void;
  onNew: () => void;
  onOpenDashboard: (dashboard: Dashboard) => void;
  onNewDashboard: () => void;
}

/** Built-in templates, saved reports (own and shared by the hospital) and dashboards. */
export function ReportList({ reports, dashboards, currentId, currentDashboardId, onOpen, onNew, onOpenDashboard, onNewDashboard }: Props) {
  const templates = reports.filter(r => r.template);
  const saved = reports.filter(r => !r.template);
  const item = (r: SavedReport) => (
    <li key={r.id}>
      <button type="button" className={`report-link${r.id === currentId ? ' current' : ''}`}
              aria-current={r.id === currentId ? 'true' : undefined} onClick={() => onOpen(r)}
              title={[r.description, r.owner ? `By ${r.owner}` : ''].filter(Boolean).join(' · ') || undefined}>
        {r.name}{r.visibility === 'HOSPITAL' && <span className="badge small">Shared</span>}
      </button>
    </li>
  );
  return (
    <nav className="report-list" aria-label="Reports">
      <button type="button" className="primary block" onClick={onNew}>+ New report</button>
      <h2>Templates</h2>
      <ul>{templates.map(item)}</ul>
      <h2>Saved reports</h2>
      {saved.length === 0 ? <p className="hint">Nothing saved yet. Build a report and choose Save as.</p> : <ul>{saved.map(item)}</ul>}
      <h2>Dashboards</h2>
      {dashboards.length > 0 && (
        <ul>
          {dashboards.map(d => (
            <li key={d.id}>
              <button type="button" className={`report-link${d.id === currentDashboardId ? ' current' : ''}`}
                      aria-current={d.id === currentDashboardId ? 'true' : undefined} onClick={() => onOpenDashboard(d)}>
                {d.name}{d.visibility === 'HOSPITAL' && <span className="badge small">Shared</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
      <button type="button" className="link" onClick={onNewDashboard}>+ New dashboard</button>
    </nav>
  );
}
