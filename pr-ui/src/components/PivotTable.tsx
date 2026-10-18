import { formatValue } from '../format';
import type { FormatRule, QueryResult } from '../types';
import { matchesRule } from './ResultsTable';

interface Props {
  result: QueryResult;
  /** the group column spread across */
  pivot: string;
  /** the measure shown in the cells */
  measure: string;
  rules?: FormatRule[];
}

const MAX_PIVOT_COLUMNS = 50;
const COLORS: Record<FormatRule['color'], string> = { red: '#fde2e1', amber: '#fff1cc', green: '#dff3e3', blue: '#e1ecfb' };

/**
 * Cross table built in the browser from all groups the server returned: the other group columns down the
 * side, the values of the pivot column across, the chosen measure in the cells. Row and column totals are
 * added up for counts and sums only.
 */
export function PivotTable({ result, pivot, measure, rules = [] }: Props) {
  const pIndex = result.columns.findIndex(c => c.key === pivot);
  const mIndex = result.columns.findIndex(c => c.key === measure);
  if (pIndex < 0 || mIndex < 0) {
    return <p className="hint">Choose a measure to pivot.</p>;
  }
  const mCol = result.columns[mIndex];
  const rowCols = result.columns.map((c, i) => ({ c, i })).filter(x => x.c.role === 'dimension' && x.i !== pIndex);
  const label = (v: unknown, i: number) => formatValue(v, result.columns[i].type, 'dimension') || '(empty)';

  const across: string[] = [];
  const raw = new Map<string, unknown>();   // sort by the values, not their text (dates, numbers)
  const rows = new Map<string, { labels: string[]; cells: Map<string, unknown> }>();
  for (const r of result.rows) {
    const a = label(r[pIndex], pIndex);
    if (!across.includes(a)) { across.push(a); raw.set(a, r[pIndex]); }
    const labels = rowCols.map(x => label(r[x.i], x.i));
    const key = JSON.stringify(labels);
    if (!rows.has(key)) rows.set(key, { labels, cells: new Map() });
    rows.get(key)!.cells.set(a, r[mIndex]);
  }
  if (across.length > MAX_PIVOT_COLUMNS) {
    return <p className="alert error">"{result.columns[pIndex].label}" has {across.length} values; a pivot can show at most {MAX_PIVOT_COLUMNS} columns. Add a filter or spread another column.</p>;
  }
  across.sort((x, y) => {
    const a = raw.get(x);
    const b = raw.get(y);
    if (a == null || b == null) return a == null ? (b == null ? 0 : 1) : -1;   // empty values last
    if (typeof a === 'number' && typeof b === 'number') return a - b;
    return String(a).localeCompare(String(b), undefined, { numeric: true });
  });
  const additive = mCol.additive === true;
  const num = (v: unknown) => (typeof v === 'number' ? v : Number(v) || 0);
  const columnTotal = (a: string) => [...rows.values()].reduce((s, r) => s + num(r.cells.get(a)), 0);
  const style = (v: unknown) => {
    const rule = rules.find(r => r.measure === measure && matchesRule(v, r));
    return rule ? { background: COLORS[rule.color], color: '#1f2a33' } : undefined;
  };

  return (
    <div className="table-wrap" tabIndex={0} aria-label="Pivot table">
      <table className="results pivot">
        <thead>
          <tr>
            {rowCols.map(x => <th key={x.c.key}><span className="th-text">{x.c.label}</span></th>)}
            {across.map(a => <th key={a} className="num"><span className="th-text">{a}</span></th>)}
            {additive && <th className="num"><span className="th-text">Total</span></th>}
          </tr>
        </thead>
        <tbody>
          {[...rows.values()].map(r => (
            <tr key={r.labels.join('|')}>
              {r.labels.map((l, i) => <td key={i}>{l}</td>)}
              {across.map(a => (
                <td key={a} className="num" style={style(r.cells.get(a))}>{formatValue(r.cells.get(a) ?? null, mCol.type, 'measure')}</td>
              ))}
              {additive && <td className="num strong">{formatValue([...r.cells.values()].reduce<number>((s, v) => s + num(v), 0), mCol.type, 'measure')}</td>}
            </tr>
          ))}
        </tbody>
        {additive && (
          <tfoot>
            <tr>
              <td colSpan={Math.max(1, rowCols.length)}>Total</td>
              {across.map(a => <td key={a} className="num">{formatValue(columnTotal(a), mCol.type, 'measure')}</td>)}
              <td className="num">{formatValue(across.reduce((s, a) => s + columnTotal(a), 0), mCol.type, 'measure')}</td>
            </tr>
          </tfoot>
        )}
      </table>
      {result.truncated && <p className="hint">Only the first {result.rows.length.toLocaleString('en-IN')} groups are shown; add filters for a complete pivot.</p>}
    </div>
  );
}
