import { type BuilderState, DISPLAY_LABELS, type HavingRow, defaultOperator, measureKey, measureOptions, newConditionId } from '../definition';
import type { CalculatedSpec, Dataset } from '../types';
import { FilterRow } from './FilterRow';

interface Props {
  dataset: Dataset;
  state: BuilderState;
  onChange: (state: BuilderState) => void;
  onRun: () => void;
  running: boolean;
  /** opens the formula editor (null: a new calculated field) */
  onEditCalculated: (spec: CalculatedSpec | null) => void;
}

const MAX_GROUP_BY = 6;

const HAVING_OPS: [HavingRow['op'], string][] = [['gt', '>'], ['gte', '≥'], ['lt', '<'], ['lte', '≤'], ['eq', '='], ['neq', '≠']];

/**
 * Left panel: what to show (columns), how to summarise (measures, shown as they are or as a share,
 * running total, rank or change), which rows (filters), the report's own calculated fields, and options
 * (subtotals, top N, pivot, conditions on totals).
 */
export function Builder({ dataset, state, onChange, onRun, running, onEditCalculated }: Props) {
  const dims = dataset.fields.filter(f => f.role === 'dimension');
  const label = (name: string) => dataset.fields.find(f => f.name === name)?.label ?? name;
  const grouped = state.measures.length > 0;
  const options = measureOptions(dataset);
  const set = (patch: Partial<BuilderState>) => onChange({ ...state, ...patch });
  const shared = dataset.fields.filter(f => f.origin === 'shared');
  const custom = dataset.fields.filter(f => f.origin === 'custom');

  const move = (index: number, delta: number) => {
    const cols = [...state.columns];
    const target = index + delta;
    if (target < 0 || target >= cols.length) return;
    [cols[index], cols[target]] = [cols[target], cols[index]];
    set({ columns: cols });
  };

  const removeCalculated = (name: string) => set({
    calculated: state.calculated.filter(c => c.name !== name),
    columns: state.columns.filter(c => c !== name),
    measures: state.measures.filter(m => m.field !== name),
    conditions: state.conditions.filter(c => c.field !== name),
    sort: state.sort.filter(s => s.field !== name),
    pivot: state.pivot === name ? null : state.pivot,
  });

  const measureChoices = (key: string) => options.filter(o => o.key === key || !state.measures.some(m => measureKey(m) === o.key && !m.display));
  const setMeasureDisplay = (index: number, display: string) => {
    const measures = state.measures.map((m, i) => (i === index ? { ...m, display: display || undefined } : m));
    set({ measures, sort: [], view: { ...state.view, rules: (state.view.rules ?? []).filter(r => measures.some(m => measureKey(m) === r.measure)) } });
  };

  return (
    <form className="builder" onSubmit={e => { e.preventDefault(); onRun(); }}>
      <section>
        <h2>{grouped ? 'Group by' : 'Columns'}</h2>
        {grouped && <p className="hint">With a summary, these columns become groups (up to {MAX_GROUP_BY}).</p>}
        <ol className="chosen">
          {state.columns.map((c, i) => (
            <li key={c}>
              <span>{label(c)}</span>
              <span className="row-actions">
                <button type="button" className="icon" aria-label={`Move ${label(c)} up`} onClick={() => move(i, -1)} disabled={i === 0}>↑</button>
                <button type="button" className="icon" aria-label={`Move ${label(c)} down`} onClick={() => move(i, 1)} disabled={i === state.columns.length - 1}>↓</button>
                <button type="button" className="icon" aria-label={`Remove ${label(c)}`}
                        onClick={() => set({ columns: state.columns.filter(x => x !== c), sort: state.sort.filter(s => s.field !== c), pivot: state.pivot === c ? null : state.pivot })}>✕</button>
              </span>
            </li>
          ))}
        </ol>
        <label className="sr-only" htmlFor="add-column">Add column</label>
        <select id="add-column" value="" onChange={e => e.target.value && set({ columns: [...state.columns, e.target.value] })}>
          <option value="">+ Add column...</option>
          {dims.filter(f => !state.columns.includes(f.name)).map(f => (
            <option key={f.name} value={f.name}>{f.label}{f.origin === 'custom' ? ' (own field)' : f.origin === 'shared' || f.origin === 'report' ? ' (calculated)' : ''}</option>
          ))}
        </select>
      </section>

      <section>
        <h2>Summarise</h2>
        {state.measures.length === 0 && <p className="hint">No summary: one row per patient. Add a measure to count or total by group.</p>}
        <ul className="chosen">
          {state.measures.map((m, i) => {
            const base = { ...m, display: undefined };
            const opt = options.find(o => o.key === measureKey(base));
            return (
              <li key={measureKey(m)} className="measure-row">
                <span>{opt?.label ?? measureKey(m)}</span>
                <span className="row-actions">
                  <select aria-label={`Show ${opt?.label} as`} value={m.display ?? ''} disabled={state.columns.length === 0 || state.subtotals}
                          onChange={e => setMeasureDisplay(i, e.target.value)}>
                    <option value="">as is</option>
                    {Object.entries(DISPLAY_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                  </select>
                  <button type="button" className="icon" aria-label={`Remove ${opt?.label}`}
                          onClick={() => set({ measures: state.measures.filter((_, x) => x !== i), sort: state.sort.filter(s => s.field !== measureKey(m)),
                                              having: state.having.filter(h => h.measure !== measureKey(m)),
                                              view: { ...state.view, rules: (state.view.rules ?? []).filter(r => r.measure !== measureKey(m)) } })}>✕</button>
                </span>
              </li>
            );
          })}
        </ul>
        <label className="sr-only" htmlFor="add-measure">Add measure</label>
        <select id="add-measure" value="" onChange={e => {
          const opt = options.find(o => o.key === e.target.value);
          if (opt) set({ measures: [...state.measures, opt.spec] });
        }}>
          <option value="">+ Add measure...</option>
          {measureChoices('').filter(o => !state.measures.some(m => measureKey(m) === o.key)).map(o => <option key={o.key} value={o.key}>{o.label}</option>)}
        </select>
      </section>

      <section>
        <h2>Calculated fields</h2>
        {state.calculated.length === 0 && shared.length === 0 && (
          <p className="hint">Your own formulas, e.g. age groups or "% insured". They work like any other field.</p>
        )}
        <ul className="chosen">
          {state.calculated.map(c => (
            <li key={c.name}>
              <span title={c.formula}>{c.label} <span className="muted small">{c.kind === 'measure' ? 'total' : 'column'}</span></span>
              <span className="row-actions">
                <button type="button" className="icon" aria-label={`Edit ${c.label}`} onClick={() => onEditCalculated(c)}>✎</button>
                <button type="button" className="icon" aria-label={`Remove ${c.label}`} onClick={() => removeCalculated(c.name)}>✕</button>
              </span>
            </li>
          ))}
        </ul>
        {shared.length > 0 && (
          <p className="hint">Shared by your hospital: {shared.map(f => f.label).join(', ')}</p>
        )}
        {custom.length > 0 && (
          <p className="hint">Your hospital's own form fields: {custom.map(f => f.label).join(', ')}</p>
        )}
        <button type="button" className="link" onClick={() => onEditCalculated(null)}>+ New calculated field</button>
      </section>

      <section>
        <h2>Filters</h2>
        {state.conditions.length > 1 && (
          <div className="match" role="radiogroup" aria-label="Combine filters">
            <label><input type="radio" checked={state.match === 'and'} onChange={() => set({ match: 'and' })} /> Match all</label>
            <label><input type="radio" checked={state.match === 'or'} onChange={() => set({ match: 'or' })} /> Match any</label>
          </div>
        )}
        {state.conditions.map(row => (
          <FilterRow key={row.id} row={row} fields={dims} dataset={dataset.id}
                     onChange={r => set({ conditions: state.conditions.map(x => (x.id === r.id ? r : x)) })}
                     onRemove={() => set({ conditions: state.conditions.filter(x => x.id !== row.id) })} />
        ))}
        <button type="button" className="link" onClick={() => {
          const f = dims.find(d => d.name === 'registration_date') ?? dims[0];
          set({ conditions: [...state.conditions, { id: newConditionId(), field: f.name, op: defaultOperator(f), text: '', text2: '' }] });
        }}>+ Add filter</button>
      </section>

      {grouped && state.columns.length > 0 && (
        <section aria-label="Options">
          <h2>Options</h2>
          <label className="check">
            <input type="checkbox" checked={state.subtotals} disabled={state.columns.length < 2}
                   onChange={e => set({ subtotals: e.target.checked, topN: null, pivot: null, having: [],
                     sort: e.target.checked ? state.sort.filter(s => state.columns.includes(s.field)) : state.sort,
                     measures: state.measures.map(m => ({ ...m, display: undefined })) })} />
            Subtotals per group {state.columns.length < 2 && <span className="muted small">(needs two group columns)</span>}
          </label>
          <label className="inline-field">Top
            <input type="number" min={1} max={1000} value={state.topN ?? ''} disabled={state.subtotals || !!state.pivot}
                   aria-label="Top N groups" placeholder="all"
                   onChange={e => set({ topN: e.target.value ? Math.max(1, Math.min(1000, Number(e.target.value))) : null })} />
            groups, the rest as "Others"
          </label>
          <label className="inline-field">Pivot: spread
            <select value={state.pivot ?? ''} disabled={state.subtotals || state.columns.length < 2 || state.topN !== null}
                    aria-label="Pivot column" onChange={e => set({ pivot: e.target.value || null })}>
              <option value="">(no pivot)</option>
              {state.columns.map(c => <option key={c} value={c}>{label(c)}</option>)}
            </select>
            across
          </label>
          <div>
            <span className="muted small">Only groups where:</span>
            {state.having.map(h => (
              <div key={h.id} className="having-row">
                <select aria-label="Measure" value={h.measure}
                        onChange={e => set({ having: state.having.map(x => (x.id === h.id ? { ...x, measure: e.target.value } : x)) })}>
                  {state.measures.filter(m => !m.display).map(m => (
                    <option key={measureKey(m)} value={measureKey(m)}>{options.find(o => o.key === measureKey(m))?.label}</option>
                  ))}
                </select>
                <select aria-label="Comparison" value={h.op}
                        onChange={e => set({ having: state.having.map(x => (x.id === h.id ? { ...x, op: e.target.value as HavingRow['op'] } : x)) })}>
                  {HAVING_OPS.map(([op, sign]) => <option key={op} value={op}>{sign}</option>)}
                </select>
                <input type="number" aria-label="Value" value={h.text}
                       onChange={e => set({ having: state.having.map(x => (x.id === h.id ? { ...x, text: e.target.value } : x)) })} />
                <button type="button" className="icon" aria-label="Remove condition"
                        onClick={() => set({ having: state.having.filter(x => x.id !== h.id) })}>✕</button>
              </div>
            ))}
            <button type="button" className="link" disabled={state.subtotals} onClick={() => {
              const first = state.measures.find(m => !m.display);
              if (first) set({ having: [...state.having, { id: newConditionId(), measure: measureKey(first), op: 'gt', text: '' }] });
            }}>+ Condition on a total</button>
          </div>
        </section>
      )}

      <section className="run-row">
        <label>Rows per page
          <select value={state.pageSize} onChange={e => set({ pageSize: Number(e.target.value) })}>
            {[25, 50, 100, 250, 500].map(n => <option key={n} value={n}>{n}</option>)}
          </select>
        </label>
        <button type="submit" className="primary" disabled={running || (state.columns.length === 0 && state.measures.length === 0)}>
          {running ? 'Running...' : 'Run'}
        </button>
      </section>
    </form>
  );
}
