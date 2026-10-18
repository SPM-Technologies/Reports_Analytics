import { type ReactNode, useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, api, contextPath, csrfToken } from './api';
import { Builder } from './components/Builder';
import { DashboardView } from './components/DashboardView';
import { FormulaEditor } from './components/FormulaEditor';
import { PivotTable } from './components/PivotTable';
import { ReportList } from './components/ReportList';
import { ResultsChart, chartable } from './components/ResultsChart';
import { ResultsTable } from './components/ResultsTable';
import { type BuilderState, emptyState, fromDefinition, groupConditions, toDefinition, toQuery, withCalculated, withConditions } from './definition';
import { formatColumnValue, formatDateTime } from './format';
import type { CalculatedSpec, ChartType, Dashboard, Dataset, FilterCondition, FormatRule, Me, QueryResult, SavedReport } from './types';

interface Current {
  id: number | null;
  name: string;
  template: boolean;
  /** templates, and other users' reports this user may not change: only "Save as" */
  readOnly: boolean;
  visibility: 'PRIVATE' | 'HOSPITAL';
  description: string;
}

/** Drill-down: the patients behind one group of a summary, and the summary to go back to. */
interface Drill {
  back: BuilderState;
  extra: FilterCondition[];
  label: string;
}

const NEW_REPORT: Current = { id: null, name: 'New report', template: false, readOnly: false, visibility: 'PRIVATE', description: '' };

const message = (e: unknown) => (e instanceof ApiError ? e.message : 'Could not reach the server. Please try again.');

function hashReportId(): number | null {
  const m = /report=(\d+)/.exec(window.location.hash);
  return m ? Number(m[1]) : null;
}

function hashDashboardId(): number | null {
  const m = /dashboard=(\d+)/.exec(window.location.hash);
  return m ? Number(m[1]) : null;
}

const RULE_OPS: [FormatRule['op'], string][] = [['gt', '>'], ['gte', '≥'], ['lt', '<'], ['lte', '≤'], ['eq', '=']];
const RULE_COLORS: FormatRule['color'][] = ['red', 'amber', 'green', 'blue'];

export function App() {
  const [baseDataset, setBaseDataset] = useState<Dataset | null>(null);
  const [reports, setReports] = useState<SavedReport[]>([]);
  const [dashboards, setDashboards] = useState<Dashboard[]>([]);
  const [state, setState] = useState<BuilderState | null>(null);
  const [current, setCurrent] = useState<Current>(NEW_REPORT);
  const [result, setResult] = useState<QueryResult | null>(null);
  const [running, setRunning] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [me, setMe] = useState<Me | null>(null);
  const [editing, setEditing] = useState<{ initial: CalculatedSpec | null } | null>(null);
  const [drill, setDrill] = useState<Drill | null>(null);
  /** undefined: the report builder; null: a new dashboard; else the open dashboard */
  const [dashboard, setDashboard] = useState<Dashboard | null | undefined>(undefined);
  const [dashboardKey, setDashboardKey] = useState(0);

  // Responses can arrive out of order (fast paging, opening a report while another loads, clicking
  // "New report" during the initial load). Only the latest request of each kind may update the screen.
  const runSeq = useRef(0);
  const viewSeq = useRef(0);

  const run = useCallback(async (s: BuilderState, offset = 0, extra: FilterCondition[] = []) => {
    const seq = ++runSeq.current;
    setRunning(true);
    setError(null);
    try {
      const r = await api.query(withConditions(toQuery(s, offset), extra));
      if (seq === runSeq.current) setResult(r);
    } catch (e) {
      if (seq === runSeq.current) setError(message(e));
    } finally {
      if (seq === runSeq.current) setRunning(false);
    }
  }, []);

  const openReport = useCallback(async (id: number) => {
    const seq = ++viewSeq.current;
    try {
      const full = await api.report(id);
      if (seq !== viewSeq.current) return;   // the user moved on meanwhile
      const s = fromDefinition(full.definition!);
      setState(s);
      setDrill(null);
      setDashboard(undefined);
      setCurrent({ id: full.id, name: full.name, template: full.template, readOnly: full.template || full.canEdit === false,
                   visibility: full.visibility ?? 'PRIVATE', description: full.description ?? '' });
      window.history.replaceState(null, '', `#report=${full.id}`);
      setNotice(null);
      await run(s);
    } catch (e) {
      setError(message(e));
    }
  }, [run]);

  const openDashboard = useCallback(async (id: number) => {
    viewSeq.current++;
    try {
      const d = await api.dashboard(id);
      setDashboard(d);
      setDashboardKey(k => k + 1);
      window.history.replaceState(null, '', `#dashboard=${d.id}`);
    } catch (e) {
      setError(message(e));
    }
  }, []);

  // Initial load: catalogue, report list, then the bookmarked report/dashboard or the Patient Register template.
  useEffect(() => {
    (async () => {
      try {
        const [user, datasets, list, boards] = await Promise.all([api.me(), api.datasets(), api.reports(), api.dashboards()]);
        setMe(user);
        setBaseDataset(datasets[0]);
        setReports(list);
        setDashboards(boards);
        const board = hashDashboardId();
        const bookmarked = hashReportId();
        const wanted = bookmarked !== null && list.some(r => r.id === bookmarked)
          ? bookmarked
          : list.find(r => r.template)?.id ?? null;
        if (wanted !== null) {
          await openReport(wanted);
        } else {
          setState(emptyState(datasets[0]));
        }
        if (board !== null && boards.some(b => b.id === board)) {
          await openDashboard(board);
        } else if ((bookmarked !== null && wanted !== bookmarked) || board !== null) {
          setNotice('The bookmarked report no longer exists; showing a template instead.');
        }
      } catch (e) {
        setError(message(e));
      }
    })();
  }, [openReport, openDashboard]);

  if (!baseDataset || !state) {
    return <Shell me={me}><p className="loading" role="status">{error ?? 'Loading reports...'}</p></Shell>;
  }

  // the catalogue plus this report's own calculated fields
  const dataset = withCalculated(baseDataset, state.calculated);
  const canShare = me?.permissions.includes('PUBLISH_SHARED') ?? false;

  const newReport = () => {
    viewSeq.current++;   // ignore a report still being opened ...
    runSeq.current++;    // ... and results of a query still running
    setRunning(false);
    const s = emptyState(baseDataset);
    setState(s);
    setCurrent(NEW_REPORT);
    setResult(null);
    setNotice(null);
    setDrill(null);
    setDashboard(undefined);
    window.history.replaceState(null, '', '#');
  };

  const newDashboard = () => {
    viewSeq.current++;
    setDashboard(null);
    setDashboardKey(k => k + 1);
    window.history.replaceState(null, '', '#');
  };

  const refreshList = async () => setReports(await api.reports());
  const refreshDashboards = async () => setDashboards(await api.dashboards());

  const saveAs = async () => {
    const name = window.prompt('Name for this report:', current.readOnly ? `${current.name} (copy)` : current.name);
    if (!name || !name.trim()) return;
    setBusy('save');
    try {
      const visibility = current.readOnly ? 'PRIVATE' : current.visibility;
      const saved = await api.createReport(name.trim(), current.description, toDefinition(state), visibility);
      setCurrent({ id: saved.id, name: saved.name, template: false, readOnly: false, visibility: saved.visibility ?? visibility, description: current.description });
      window.history.replaceState(null, '', `#report=${saved.id}`);
      await refreshList();
      setNotice(`Saved as "${saved.name}".`);
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(null);
    }
  };

  const save = async () => {
    if (current.id === null || current.readOnly) {
      await saveAs();
      return;
    }
    setBusy('save');
    try {
      await api.updateReport(current.id, current.name, current.description, toDefinition(state), current.visibility);
      await refreshList();
      setNotice(`Saved "${current.name}".`);
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(null);
    }
  };

  const remove = async () => {
    if (current.id === null || current.readOnly || !window.confirm(`Delete the report "${current.name}"?`)) return;
    try {
      await api.deleteReport(current.id);
      await refreshList();
      newReport();
      setNotice('Report deleted.');
    } catch (e) {
      setError(message(e));
    }
  };

  const download = async (format: 'xlsx' | 'pdf' | 'csv') => {
    setBusy(format);
    setError(null);
    try {
      await api.download(withConditions(toQuery(state), drill?.extra ?? []), format, drill ? `${current.name} - ${drill.label}` : current.name);
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(null);
    }
  };

  // Header click: none -> ascending -> descending -> none (server-side sort, back to page 1).
  const toggleSort = (field: string) => {
    if (state.subtotals && !state.columns.includes(field)) {
      setNotice('With subtotals, sort by the group columns only.');
      return;
    }
    const existing = state.sort.find(s => s.field === field);
    const sort = !existing ? [{ field, dir: 'asc' as const }] : existing.dir === 'asc' ? [{ field, dir: 'desc' as const }] : [];
    const s = { ...state, sort };
    setState(s);
    run(s, 0, drill?.extra);
  };

  /** Saves the formula editor: as this report's own field, or shared with the hospital (then it is in the catalogue). */
  const saveCalculated = async (spec: CalculatedSpec, share: boolean, description: string) => {
    if (share) {
      const f = await api.createSharedField(baseDataset.id, spec.label, spec.formula, description);
      const datasets = await api.datasets();
      setBaseDataset(datasets.find(d => d.id === baseDataset.id) ?? datasets[0]);
      setNotice(`"${f.label}" is now shared with your hospital.`);
    } else {
      const old = state.calculated.find(c => c.name === spec.name);
      const kindChanged = old !== undefined && (old.kind !== spec.kind || old.type !== spec.type);
      const calculated = old ? state.calculated.map(c => (c.name === spec.name ? spec : c)) : [...state.calculated, spec];
      setState({
        ...state,
        calculated,
        // a formula that turned from a column into a total (or back) cannot stay where it was used
        columns: kindChanged ? state.columns.filter(c => c !== spec.name) : state.columns,
        measures: kindChanged ? state.measures.filter(m => m.field !== spec.name) : state.measures,
        conditions: kindChanged ? state.conditions.filter(c => c.field !== spec.name) : state.conditions,
        sort: kindChanged ? state.sort.filter(s => !s.field.endsWith(spec.name)) : state.sort,
        having: kindChanged ? state.having.filter(h => !h.measure.endsWith(spec.name)) : state.having,
        pivot: kindChanged && state.pivot === spec.name ? null : state.pivot,
        view: kindChanged ? { ...state.view, rules: (state.view.rules ?? []).filter(r => !r.measure.endsWith(spec.name)) } : state.view,
      });
    }
    setEditing(null);
  };

  /** Opens the patients behind one group of a summary (the summary's filters plus the group's values). */
  const drillDown = (row: unknown[]) => {
    if (!result) return;
    const dims = result.columns.map((c, i) => ({ c, i })).filter(x => x.c.role === 'dimension');
    if (dims.some(x => x.c.type !== 'boolean' && (row[x.i] === null || row[x.i] === undefined))) {
      setNotice('A group with an empty value cannot be opened; add a filter instead.');
      return;
    }
    const extra = groupConditions(result.columns, row);
    const label = dims.map(x => `${x.c.label}: ${formatColumnValue(row[x.i], x.c) || '(empty)'}`).join(', ');
    const detail: BuilderState = {
      ...emptyState(baseDataset),
      calculated: state.calculated,
      conditions: state.conditions,
      match: state.match,
    };
    setDrill({ back: state, extra, label });
    setState(detail);
    setNotice(null);
    run(detail, 0, extra);
  };

  const closeDrill = () => {
    if (!drill) return;
    setState(drill.back);
    setDrill(null);
    run(drill.back);
  };

  if (dashboard !== undefined) {
    return (
      <Shell me={me}>
        <div className="layout dashboard-layout">
          <ReportList reports={reports} dashboards={dashboards} currentId={null} currentDashboardId={dashboard?.id ?? null}
                      onOpen={r => openReport(r.id)} onNew={newReport}
                      onOpenDashboard={d => openDashboard(d.id)} onNewDashboard={newDashboard} />
          <DashboardView key={dashboardKey} dataset={baseDataset} dashboard={dashboard} reports={reports} canShare={canShare}
                         onSaved={async d => { await refreshDashboards(); setDashboard(d); window.history.replaceState(null, '', `#dashboard=${d.id}`); }}
                         onDeleted={async () => { await refreshDashboards(); newReport(); setNotice('Dashboard deleted.'); }} />
        </div>
      </Shell>
    );
  }

  const pivoted = result !== null && result.mode === 'aggregate' && !drill && toQuery(state).pivot !== undefined;
  const pages = result ? Math.max(1, Math.ceil(result.totalRows / result.pageSize)) : 1;
  const page = result ? Math.floor(result.offset / result.pageSize) + 1 : 1;
  const canChart = result !== null && chartable(result);
  const showChart = state.view.mode === 'chart' && canChart;
  const setView = (patch: Partial<BuilderState['view']>) => setState({ ...state, view: { ...state.view, ...patch } });
  const measureCols = result ? result.columns.filter(c => c.role === 'measure') : [];
  const rules = state.view.rules ?? [];
  const setRule = (i: number, patch: Partial<FormatRule>) => setView({ rules: rules.map((r, j) => (j === i ? { ...r, ...patch } : r)) });
  const pivotMeasure = state.view.pivotMeasure && measureCols.some(c => c.key === state.view.pivotMeasure)
    ? state.view.pivotMeasure : measureCols[0]?.key;

  return (
    <Shell me={me}>
      <div className="layout">
        <ReportList reports={reports} dashboards={dashboards} currentId={current.id} currentDashboardId={null}
                    onOpen={r => openReport(r.id)} onNew={newReport}
                    onOpenDashboard={d => openDashboard(d.id)} onNewDashboard={newDashboard} />
        <Builder dataset={dataset} state={state} onChange={setState} onRun={() => run(state, 0, drill?.extra)} running={running}
                 onEditCalculated={spec => setEditing({ initial: spec })} />

        <main className="results-panel" aria-busy={running}>
          <header className="results-head">
            <div>
              <h1>{current.name}{current.template && <span className="badge">Template</span>}
                {!current.template && current.readOnly && <span className="badge">Shared · read-only</span>}
                {!current.readOnly && current.id !== null && current.visibility === 'HOSPITAL' && <span className="badge">Shared</span>}</h1>
              {result && (
                <p className="meta" role="status">
                  {result.totalRows.toLocaleString('en-IN')} {result.mode === 'aggregate' ? 'groups' : 'rows'}
                  {' · '}{(result.queryMs / 1000).toFixed(2)} s
                  {' · '}Data as of {formatDateTime(result.dataAsOf)}
                </p>
              )}
            </div>
            <div className="actions">
              {!drill && (
                <>
                  {canShare && !current.readOnly && (
                    <select aria-label="Who sees this report" value={current.visibility}
                            onChange={e => setCurrent({ ...current, visibility: e.target.value as Current['visibility'] })}>
                      <option value="PRIVATE">Only me</option>
                      <option value="HOSPITAL">Everyone in my hospital</option>
                    </select>
                  )}
                  <button type="button" onClick={save} disabled={busy !== null}>{current.id !== null && !current.readOnly ? 'Save' : 'Save as'}</button>
                  {current.id !== null && !current.readOnly && <button type="button" onClick={saveAs} disabled={busy !== null}>Save as</button>}
                  {current.id !== null && !current.readOnly && <button type="button" className="danger" onClick={remove}>Delete</button>}
                </>
              )}
              {me?.canExport !== false && (
                <>
                  <span className="divider" />
                  <button type="button" onClick={() => download('xlsx')} disabled={busy !== null || !result}>{busy === 'xlsx' ? 'Preparing...' : 'Excel'}</button>
                  <button type="button" onClick={() => download('pdf')} disabled={busy !== null || !result}>{busy === 'pdf' ? 'Preparing...' : 'PDF'}</button>
                  <button type="button" onClick={() => download('csv')} disabled={busy !== null || !result}>{busy === 'csv' ? 'Preparing...' : 'CSV'}</button>
                </>
              )}
            </div>
          </header>

          {drill && (
            <div className="drill-bar" role="status">
              <button type="button" onClick={closeDrill}>‹ Back to the summary</button>
              <span>Patients of <strong>{drill.label}</strong></span>
            </div>
          )}
          {error && <div className="alert error" role="alert">{error}</div>}
          {notice && !error && <div className="alert ok" role="status">{notice}</div>}

          {result && (
            <>
              <div className="view-bar">
                <div className="segmented" role="group" aria-label="View">
                  <button type="button" aria-pressed={!showChart} onClick={() => setView({ mode: 'table' })}>{pivoted ? 'Pivot' : 'Table'}</button>
                  <button type="button" aria-pressed={showChart} disabled={!canChart}
                          title={canChart ? undefined : 'Add a measure and a group column to chart'}
                          onClick={() => setView({ mode: 'chart', chart: state.view.chart ?? 'bar' })}>Chart</button>
                </div>
                {showChart && (
                  <label>Chart type
                    <select value={state.view.chart ?? 'bar'} onChange={e => setView({ chart: e.target.value as ChartType })}>
                      <option value="bar">Bar</option>
                      <option value="line">Line</option>
                      <option value="pie">Pie</option>
                    </select>
                  </label>
                )}
                {pivoted && !showChart && measureCols.length > 1 && (
                  <label>Cells show
                    <select value={pivotMeasure} onChange={e => setView({ pivotMeasure: e.target.value })}>
                      {measureCols.map(c => <option key={c.key} value={c.key}>{c.label}</option>)}
                    </select>
                  </label>
                )}
                {!showChart && measureCols.length > 0 && (
                  <details className="rules">
                    <summary>Highlight{rules.length > 0 ? ` (${rules.length})` : ''}</summary>
                    <div className="rules-panel">
                      {rules.map((r, i) => (
                        <div key={i} className="rule-row">
                          <select aria-label="Highlight measure" value={r.measure} onChange={e => setRule(i, { measure: e.target.value })}>
                            {measureCols.map(c => <option key={c.key} value={c.key}>{c.label}</option>)}
                          </select>
                          <select aria-label="Highlight comparison" value={r.op} onChange={e => setRule(i, { op: e.target.value as FormatRule['op'] })}>
                            {RULE_OPS.map(([op, sign]) => <option key={op} value={op}>{sign}</option>)}
                          </select>
                          <input type="number" aria-label="Highlight value" value={Number.isFinite(r.value) ? r.value : ''}
                                 onChange={e => setRule(i, { value: Number(e.target.value) })} />
                          <select aria-label="Highlight colour" value={r.color} onChange={e => setRule(i, { color: e.target.value as FormatRule['color'] })}>
                            {RULE_COLORS.map(c => <option key={c} value={c}>{c}</option>)}
                          </select>
                          <button type="button" className="icon" aria-label="Remove highlight"
                                  onClick={() => setView({ rules: rules.filter((_, j) => j !== i) })}>✕</button>
                        </div>
                      ))}
                      <button type="button" className="link"
                              onClick={() => setView({ rules: [...rules, { measure: measureCols[0].key, op: 'gt', value: 0, color: 'green' }] })}>
                        + Highlight cells
                      </button>
                    </div>
                  </details>
                )}
              </div>
              {showChart ? <ResultsChart result={result} type={state.view.chart ?? 'bar'} />
                : pivoted ? <PivotTable result={result} pivot={toQuery(state).pivot!} measure={pivotMeasure ?? ''} rules={rules} />
                : <ResultsTable result={result} sort={state.sort} onSort={toggleSort} rules={rules}
                                onDrill={drill ? undefined : drillDown} />}
              {!showChart && !pivoted && pages > 1 && toQuery(state).topN === undefined && (
                <nav className="pager" aria-label="Pages">
                  <button type="button" disabled={page <= 1 || running} onClick={() => run(state, 0, drill?.extra)}>« First</button>
                  <button type="button" disabled={page <= 1 || running} onClick={() => run(state, result.offset - result.pageSize, drill?.extra)}>‹ Prev</button>
                  <span>Page {page.toLocaleString('en-IN')} of {pages.toLocaleString('en-IN')}</span>
                  <button type="button" disabled={page >= pages || running} onClick={() => run(state, result.offset + result.pageSize, drill?.extra)}>Next ›</button>
                  <button type="button" disabled={page >= pages || running} onClick={() => run(state, (pages - 1) * result.pageSize, drill?.extra)}>Last »</button>
                </nav>
              )}
            </>
          )}
          {!result && !error && <p className="hint big">Choose columns and filters, then press Run.</p>}
        </main>
      </div>
      {editing && (
        <FormulaEditor dataset={dataset}
                       earlier={editing.initial ? state.calculated.slice(0, state.calculated.findIndex(c => c.name === editing.initial!.name)) : state.calculated}
                       initial={editing.initial} canShare={canShare}
                       onSave={saveCalculated} onCancel={() => setEditing(null)} />
      )}
    </Shell>
  );
}

/**
 * Top bar shared with the JSP screens' look: links back to them (only those the user may use), the
 * hospitals whose data the reports cover, the user and Sign out (a form POST with the CSRF token).
 */
function Shell({ me, children }: { me: Me | null; children: ReactNode }) {
  const can = (p: string) => me?.permissions.includes(p) ?? false;
  const hospitals = me?.analyticsHospitals ?? [];
  return (
    <>
      <header className="topbar">
        <div className="brand">✚ Patient Registration <span>Analytics</span></div>
        <nav aria-label="Application">
          <a href={`${contextPath}/`}>Dashboard</a>
          {can('REGISTER') && <a href={`${contextPath}/patient/new`}>New Registration</a>}
          {can('LEGACY_REPORTS') && <a href={`${contextPath}/reports/register`}>Patient Register</a>}
          {can('LEGACY_REPORTS') && <a href={`${contextPath}/reports/summary`}>Department Summary</a>}
          <a href={`${contextPath}/app/`} className="active" aria-current="page">Analytics</a>
        </nav>
        {me && (
          <div className="userbar">
            <span className="hospital" title={hospitals.map(h => `${h.code} - ${h.name}`).join('\n')}>
              {hospitals.length === 1 ? `${hospitals[0].code} - ${hospitals[0].name}` : `${hospitals.length} hospitals`}
            </span>
            <span>{me.displayName}</span>
            <form method="post" action={`${contextPath}/logout`} className="inline">
              <input type="hidden" name="_csrf" value={csrfToken()} />
              <button type="submit" className="link">Sign out</button>
            </form>
          </div>
        )}
      </header>
      {children}
    </>
  );
}
