import { type ColumnDef, flexRender, getCoreRowModel, useReactTable } from '@tanstack/react-table';
import { useMemo } from 'react';
import { formatColumnValue, formatValue, isNumeric } from '../format';
import type { FormatRule, QueryResult, SortSpec } from '../types';

interface Props {
  result: QueryResult;
  sort: SortSpec[];
  onSort: (field: string) => void;
  /** conditional formatting of measure cells */
  rules?: FormatRule[];
  /** aggregate rows: open the patients behind a group */
  onDrill?: (row: unknown[]) => void;
}

/** Background of a highlighted cell, by rule colour. */
const COLORS: Record<FormatRule['color'], string> = { red: '#fde2e1', amber: '#fff1cc', green: '#dff3e3', blue: '#e1ecfb' };

export function matchesRule(value: unknown, rule: FormatRule): boolean {
  const n = typeof value === 'number' ? value : Number(value);
  if (value === null || value === undefined || Number.isNaN(n)) return false;
  switch (rule.op) {
    case 'gt': return n > rule.value;
    case 'gte': return n >= rule.value;
    case 'lt': return n < rule.value;
    case 'lte': return n <= rule.value;
    default: return n === rule.value;
  }
}

/**
 * Result grid. Sorting is done by the server (click a header), so the grid only renders one page.
 * With subtotals the server marks each row's level (meta column __level): subtotal rows are shown bold with
 * "Subtotal" in place of the rolled-up columns. With top N an "Others" row adds up the remaining groups.
 */
export function ResultsTable({ result, sort, onSort, rules = [], onDrill }: Props) {
  const visible = result.columns.map((c, i) => ({ c, i })).filter(x => x.c.role !== 'meta');
  const levelIndex = result.columns.findIndex(c => c.key === '__level');
  const columns = useMemo<ColumnDef<unknown[]>[]>(
    () => visible.map(({ c, i }) => ({
      id: c.key,
      header: c.label,
      accessorFn: row => row[i],
      cell: info => formatColumnValue(info.getValue(), c),
      meta: { numeric: isNumeric(c.type, c.role), index: i },
    })),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [result.columns],
  );
  const table = useReactTable({ data: result.rows, columns, getCoreRowModel: getCoreRowModel() });
  const dims = result.columns.filter(c => c.role === 'dimension').length;
  const highlight = (key: string, value: unknown) => {
    const r = rules.find(rule => rule.measure === key && matchesRule(value, rule));
    return r ? { background: COLORS[r.color], color: '#1f2a33' } : undefined;
  };

  return (
    <div className="table-wrap" tabIndex={0} aria-label="Report results">
      <table className="results">
        <thead>
          {table.getHeaderGroups().map(group => (
            <tr key={group.id}>
              <th className="num">#</th>
              {group.headers.map(h => {
                const s = sort.find(x => x.field === h.column.id);
                const numeric = (h.column.columnDef.meta as { numeric: boolean }).numeric;
                return (
                  <th key={h.id} className={numeric ? 'num' : undefined}
                      aria-sort={s ? (s.dir === 'asc' ? 'ascending' : 'descending') : 'none'}>
                    <button type="button" className="sort" onClick={() => onSort(h.column.id)} title="Sort">
                      {flexRender(h.column.columnDef.header, h.getContext())}
                      <span className="sort-mark">{s ? (s.dir === 'asc' ? ' ▲' : ' ▼') : ''}</span>
                    </button>
                  </th>
                );
              })}
            </tr>
          ))}
        </thead>
        <tbody>
          {table.getRowModel().rows.map((row, n) => {
            const level = levelIndex >= 0 ? Number(row.original[levelIndex] ?? 0) : 0;
            const subtotal = level > 0;
            const drillable = onDrill && !subtotal && result.mode === 'aggregate' && dims > 0;
            return (
              <tr key={row.id} className={`${subtotal ? 'subtotal' : ''}${drillable ? ' drillable' : ''}`}
                  onClick={drillable ? () => onDrill!(row.original) : undefined}
                  title={drillable ? 'Show the patients of this group' : undefined}>
                <td className="num muted">{subtotal ? '' : result.offset + n + 1}</td>
                {row.getVisibleCells().map((cell, ci) => {
                  const col = visible[ci].c;
                  const value = cell.getValue();
                  // grouping() sets one bit per rolled-up group column (the last column is the lowest bit);
                  // the first rolled-up column reads "Subtotal", the others stay empty
                  const rolled = (d: number) => ((level >> (dims - 1 - d)) & 1) === 1;
                  const text = subtotal && col.role === 'dimension' && ci < dims && rolled(ci)
                    ? ([...Array(ci).keys()].some(rolled) ? '' : 'Subtotal')
                    : null;
                  return (
                    <td key={cell.id} className={(cell.column.columnDef.meta as { numeric: boolean }).numeric ? 'num' : undefined}
                        style={col.role === 'measure' ? highlight(col.key, value) : undefined}>
                      {text ?? flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </td>
                  );
                })}
              </tr>
            );
          })}
          {result.others && (
            <tr className="others">
              <td className="num muted"></td>
              {visible.map(({ c, i }) => (
                <td key={c.key} className={c.role === 'measure' ? 'num' : undefined}>
                  {c.role === 'dimension' ? (i === 0 ? 'Others' : '') : formatValue(result.others![i], c.type, c.role)}
                </td>
              ))}
            </tr>
          )}
          {result.rows.length === 0 && (
            <tr><td colSpan={visible.length + 1} className="empty">No rows match these filters.</td></tr>
          )}
        </tbody>
        {result.totals && (
          <tfoot>
            <tr>
              <td>Total</td>
              {visible.map(({ c, i }) => (
                <td key={c.key} className={c.role === 'measure' ? 'num' : undefined}>
                  {i < dims ? '' : formatValue(result.totals![i - dims], c.type, c.role)}
                </td>
              ))}
            </tr>
          </tfoot>
        )}
      </table>
    </div>
  );
}
