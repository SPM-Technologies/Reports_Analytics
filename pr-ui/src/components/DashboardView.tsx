import { useEffect, useState } from 'react';
import { ApiError, api } from '../api';
import { type ConditionRow, defaultOperator, fromConditions, newConditionId, toConditions, withConditions } from '../definition';
import type { ChartType, Dashboard, DashboardTile, Dataset, FilterCondition, QueryResult, SavedReport } from '../types';
import { FilterRow } from './FilterRow';
import { ResultsChart, chartable } from './ResultsChart';
import { ResultsTable } from './ResultsTable';

interface Props {
  dataset: Dataset;
  dashboard: Dashboard | null;          // null: a new dashboard
  reports: SavedReport[];
  canShare: boolean;
  onSaved: (d: Dashboard) => void;
  onDeleted: () => void;
}

/**
 * Tiles share the user's query allowance (the server runs at most 3 queries per user at a time): they queue
 * here and run one at a time (retrying briefly when another report of the user is still running).
 */
const MAX_PARALLEL_TILES = 1;
let runningTiles = 0;
const waitingTiles: (() => void)[] = [];
async function inTileQueue<T>(work: () => Promise<T>): Promise<T> {
  if (runningTiles >= MAX_PARALLEL_TILES) {
    await new Promise<void>(resolve => waitingTiles.push(resolve));
  }
  runningTiles++;
  try {
    // the user may still have another report running (e.g. the page that was open): retry briefly
    for (let attempt = 1; ; attempt++) {
      try {
        return await work();
      } catch (e) {
        if (!(e instanceof ApiError) || e.status !== 429 || attempt >= 5) throw e;
        await new Promise(resolve => setTimeout(resolve, 1000 * attempt));
      }
    }
  } finally {
    runningTiles--;
    waitingTiles.shift()?.();
  }
}

const message = (e: unknown) => (e instanceof ApiError ? e.message : 'Could not reach the server. Please try again.');

/**
 * A dashboard: saved reports as tiles, all filtered by the dashboard's filters. Each tile runs its report as
 * the viewer (own hospitals, own field rules); filters on fields a report cannot use are left out for it.
 */
export function DashboardView({ dataset, dashboard, reports, canShare, onSaved, onDeleted }: Props) {
  const [name, setName] = useState(dashboard?.name ?? 'New dashboard');
  const [tiles, setTiles] = useState<DashboardTile[]>(dashboard?.definition?.tiles ?? []);
  const [filters, setFilters] = useState<ConditionRow[]>(fromConditions(dashboard?.definition?.filters ?? []));
  const [applied, setApplied] = useState<FilterCondition[]>(dashboard?.definition?.filters ?? []);
  const [visibility, setVisibility] = useState<'PRIVATE' | 'HOSPITAL'>(dashboard?.visibility ?? 'PRIVATE');
  const [editing, setEditing] = useState(dashboard === null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const dims = dataset.fields.filter(f => f.role === 'dimension');
  const canEdit = dashboard === null || dashboard.canEdit;

  const save = async () => {
    setError(null);
    try {
      const definition = { tiles, filters: toConditions(filters) };
      const saved = dashboard === null
        ? await api.createDashboard(name.trim(), definition, visibility)
        : await api.updateDashboard(dashboard.id, name.trim(), definition, visibility);
      setNotice('Dashboard saved.');
      setEditing(false);
      onSaved(saved);
    } catch (e) {
      setError(message(e));
    }
  };

  const remove = async () => {
    if (!dashboard || !window.confirm(`Delete the dashboard "${dashboard.name}"?`)) return;
    try {
      await api.deleteDashboard(dashboard.id);
      onDeleted();
    } catch (e) {
      setError(message(e));
    }
  };

  const reportName = (id: number) => reports.find(r => r.id === id)?.name ?? `Report ${id}`;

  return (
    <main className="results-panel dashboard" aria-label="Dashboard">
      <header className="results-head">
        <div>
          {editing ? (
            <input className="title-input" aria-label="Dashboard name" value={name} maxLength={120} onChange={e => setName(e.target.value)} />
          ) : (
            <h1>{name}{dashboard?.visibility === 'HOSPITAL' && <span className="badge">Shared</span>}</h1>
          )}
          {dashboard?.owner && <p className="meta">By {dashboard.owner}{dashboard.hospital ? ` · ${dashboard.hospital}` : ''}</p>}
        </div>
        <div className="actions">
          {canEdit && !editing && <button type="button" onClick={() => setEditing(true)}>Edit</button>}
          {editing && (
            <>
              {canShare && (
                <select aria-label="Who sees this dashboard" value={visibility} onChange={e => setVisibility(e.target.value as 'PRIVATE' | 'HOSPITAL')}>
                  <option value="PRIVATE">Only me</option>
                  <option value="HOSPITAL">Everyone in my hospital</option>
                </select>
              )}
              <button type="button" className="primary" onClick={save} disabled={!name.trim() || tiles.length === 0}>Save</button>
            </>
          )}
          {dashboard && canEdit && <button type="button" className="danger" onClick={remove}>Delete</button>}
        </div>
      </header>
      {error && <div className="alert error" role="alert">{error}</div>}
      {notice && !error && <div className="alert ok" role="status">{notice}</div>}

      <section className="dash-filters" aria-label="Dashboard filters">
        {filters.map(row => (
          <FilterRow key={row.id} row={row} fields={dims} dataset={dataset.id}
                     onChange={r => setFilters(filters.map(x => (x.id === r.id ? r : x)))}
                     onRemove={() => setFilters(filters.filter(x => x.id !== row.id))} />
        ))}
        <div className="actions">
          <button type="button" className="link" onClick={() => {
            const f = dims.find(d => d.name === 'registration_date') ?? dims[0];
            setFilters([...filters, { id: newConditionId(), field: f.name, op: defaultOperator(f), text: '', text2: '' }]);
          }}>+ Filter for all tiles</button>
          {filters.length > 0 && <button type="button" onClick={() => setApplied(toConditions(filters))}>Apply filters</button>}
        </div>
      </section>

      {editing && (
        <section className="dash-edit">
          <select aria-label="Add a report as a tile" value="" onChange={e => {
            if (e.target.value) setTiles([...tiles, { reportId: Number(e.target.value), width: 1, view: 'chart' }]);
          }}>
            <option value="">+ Add a report...</option>
            {reports.map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
          </select>
          {tiles.length === 0 && <p className="hint">Add saved reports or templates as tiles.</p>}
        </section>
      )}

      <div className="tiles-grid">
        {tiles.map((t, i) => (
          <div key={`${t.reportId}-${i}`} className={`tile-card${t.width === 2 ? ' wide' : ''}`}>
            <div className="tile-head">
              <strong>{t.title || reportName(t.reportId)}</strong>
              {editing && (
                <span className="row-actions">
                  <select aria-label="Tile view" value={t.view ?? 'chart'}
                          onChange={e => setTiles(tiles.map((x, j) => (j === i ? { ...x, view: e.target.value as 'table' | 'chart' } : x)))}>
                    <option value="chart">Chart</option>
                    <option value="table">Table</option>
                  </select>
                  <button type="button" className="icon" aria-label="Wider or narrower"
                          onClick={() => setTiles(tiles.map((x, j) => (j === i ? { ...x, width: x.width === 2 ? 1 : 2 } : x)))}>⇔</button>
                  <button type="button" className="icon" aria-label="Move left" disabled={i === 0}
                          onClick={() => { const n = [...tiles]; [n[i - 1], n[i]] = [n[i], n[i - 1]]; setTiles(n); }}>←</button>
                  <button type="button" className="icon" aria-label="Remove tile" onClick={() => setTiles(tiles.filter((_, j) => j !== i))}>✕</button>
                </span>
              )}
            </div>
            <Tile tile={t} filters={applied} dataset={dataset} />
          </div>
        ))}
      </div>
    </main>
  );
}

/** One tile: runs its saved report with the dashboard filters added (fields it does not have are skipped). */
function Tile({ tile, filters, dataset }: { tile: DashboardTile; filters: FilterCondition[]; dataset: Dataset }) {
  const [result, setResult] = useState<QueryResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [chartType, setChartType] = useState<ChartType>('bar');
  const key = JSON.stringify([tile.reportId, tile.view, filters]);

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        setError(null);
        const report = await api.report(tile.reportId);
        if (alive) setChartType(report.definition?.view?.chart ?? 'bar');
        const q = report.definition!.query;
        const usable = filters.filter(f => dataset.fields.some(x => x.name === f.field));
        const query = withConditions({ ...q, offset: 0, pageSize: tile.view === 'table' ? 10 : 100, topN: q.topN, pivot: undefined }, usable);
        const r = await inTileQueue(() => api.query(query));
        if (alive) setResult(r);
      } catch (e) {
        if (alive) setError(e instanceof ApiError ? e.message : 'Could not load this tile.');
      }
    })();
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  if (error) return <p className="alert error">{error}</p>;
  if (!result) return <p className="loading" role="status">Loading...</p>;
  const chart = tile.view !== 'table' && chartable(result);
  return chart ? <ResultsChart result={result} type={chartType} /> : (
    <>
      <ResultsTable result={result} sort={[]} onSort={() => undefined} />
      <p className="meta">{result.totalRows.toLocaleString('en-IN')} {result.mode === 'aggregate' ? 'groups' : 'rows'}</p>
    </>
  );
}
