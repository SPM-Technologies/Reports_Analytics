import type { CalculatedSpec, Dataset, Field, FilterCondition, FilterNode, MeasureSpec, QueryRequest, ReportDefinition, SortSpec, ViewSettings } from './types';

/** A condition on a total, e.g. Patients greater than 500. */
export interface HavingRow {
  id: number;
  measure: string;
  op: 'gt' | 'gte' | 'lt' | 'lte' | 'eq' | 'neq';
  text: string;
}

/** One editable filter row. Values are kept as typed text and converted when the query is built. */
export interface ConditionRow {
  id: number;
  field: string;
  op: string;
  text: string;      // eq/neq/contains/startsWith/gt..lte/relative; comma-separated list for in/notIn
  text2: string;     // second bound for "between"
}

/** Everything the builder edits. */
export interface BuilderState {
  dataset: string;
  columns: string[];
  measures: MeasureSpec[];
  match: 'and' | 'or';
  conditions: ConditionRow[];
  sort: SortSpec[];
  pageSize: number;
  view: ViewSettings;
  /** this report's own formulas */
  calculated: CalculatedSpec[];
  subtotals: boolean;
  topN: number | null;
  having: HavingRow[];
  pivot: string | null;
}

let nextId = 1;
export const newConditionId = () => nextId++;

export const OPERATOR_LABELS: Record<string, string> = {
  eq: 'is',
  neq: 'is not',
  in: 'is any of',
  notIn: 'is none of',
  contains: 'contains',
  startsWith: 'starts with',
  gt: 'greater than',
  gte: 'at least',
  lt: 'less than',
  lte: 'at most',
  between: 'between',
  relative: 'in period',
  isTrue: 'is Yes',
  isFalse: 'is No',
};

/** Preferred operator order in the drop-down. */
const OPERATOR_ORDER = Object.keys(OPERATOR_LABELS);
export const sortedOperators = (ops: string[]) => [...ops].sort((a, b) => OPERATOR_ORDER.indexOf(a) - OPERATOR_ORDER.indexOf(b));

export const RELATIVE_PERIODS: [string, string][] = [
  ['TODAY', 'Today'],
  ['YESTERDAY', 'Yesterday'],
  ['LAST_7_DAYS', 'Last 7 days'],
  ['LAST_30_DAYS', 'Last 30 days'],
  ['THIS_MONTH', 'This month'],
  ['LAST_MONTH', 'Last month'],
  ['THIS_YEAR', 'This year'],
  ['LAST_YEAR', 'Last year'],
];

export const AGG_LABELS: Record<string, string> = {
  sum: 'Total',
  avg: 'Average',
  min: 'Lowest',
  max: 'Highest',
  countDistinct: 'Distinct',
};

/** Measure choices: fixed measures (e.g. Patients) and every allowed aggregation of a dimension. */
export function measureOptions(ds: Dataset): { key: string; spec: MeasureSpec; label: string }[] {
  const out: { key: string; spec: MeasureSpec; label: string }[] = [];
  for (const f of ds.fields) {
    if (f.role === 'measure') {
      out.push({ key: f.name, spec: { field: f.name }, label: f.label });
    } else {
      for (const agg of f.aggregations) {
        out.push({ key: `${agg}_${f.name}`, spec: { field: f.name, agg }, label: `${AGG_LABELS[agg] ?? agg} ${f.label}` });
      }
    }
  }
  return out;
}

/** Key of a measure's result column, as the server names it. */
export const measureKey = (m: MeasureSpec) => {
  const base = m.agg ? `${m.agg}_${m.field}` : m.field;
  return m.display ? `${m.display}_${base}` : base;
};

export const DISPLAY_LABELS: Record<string, string> = {
  percentOfTotal: '% of total',
  percentOfGroup: '% of group',
  runningTotal: 'Running total',
  rank: 'Rank',
  change: 'Change from previous',
  percentChange: '% change from previous',
};

const OPERATORS_BY_TYPE: Record<string, string[]> = {
  string: ['eq', 'neq', 'in', 'notIn', 'contains', 'startsWith'],
  number: ['eq', 'neq', 'in', 'gt', 'gte', 'lt', 'lte', 'between'],
  money: ['eq', 'neq', 'gt', 'gte', 'lt', 'lte', 'between'],
  date: ['eq', 'gt', 'gte', 'lt', 'lte', 'between', 'relative'],
  boolean: ['isTrue', 'isFalse'],
};

/** The dataset plus this report's own calculated fields (as columns or measures, once checked). */
export function withCalculated(ds: Dataset, calculated: CalculatedSpec[]): Dataset {
  const extra: Field[] = calculated.filter(c => c.kind).map(c => ({
    name: c.name,
    label: c.label,
    type: c.type ?? 'string',
    role: c.kind === 'measure' ? 'measure' : 'dimension',
    operators: c.kind === 'measure' ? [] : OPERATORS_BY_TYPE[c.type ?? 'string'],
    aggregations: c.kind === 'measure' ? [] : c.type === 'number' ? ['sum', 'avg', 'min', 'max'] : c.type === 'boolean' ? ['sum'] : ['countDistinct'],
    suggest: false,
    origin: 'report',
    formula: c.formula,
  }));
  return extra.length ? { ...ds, fields: [...ds.fields, ...extra] } : ds;
}

export function emptyState(ds: Dataset): BuilderState {
  return {
    dataset: ds.id,
    columns: ['registration_no', 'registration_date', 'patient_name', 'gender', 'dept_name'].filter(n => ds.fields.some(f => f.name === n)),
    measures: [],
    match: 'and',
    conditions: [],
    sort: [],
    pageSize: 50,
    view: { mode: 'table' },
    calculated: [],
    subtotals: false,
    topN: null,
    having: [],
    pivot: null,
  };
}

export function defaultOperator(f: Field): string {
  if (f.type === 'date') return 'relative';
  if (f.type === 'boolean') return 'isTrue';
  if (f.type === 'string') return f.suggest ? 'in' : 'contains';
  return 'gte';
}

/** Converts typed text into the value the API expects for the operator; null if incomplete. */
function conditionValue(row: ConditionRow): unknown | null {
  const t = row.text.trim();
  switch (row.op) {
    case 'isTrue':
    case 'isFalse':
      return undefined;
    case 'in':
    case 'notIn': {
      const list = row.text.split(',').map(s => s.trim()).filter(Boolean);
      return list.length ? list : null;
    }
    case 'between':
      return t && row.text2.trim() ? [t, row.text2.trim()] : null;
    default:
      return t ? t : null;
  }
}

/** Builds the API request; incomplete filter rows are skipped (the UI marks them). */
export function toQuery(s: BuilderState, offset = 0): QueryRequest {
  const conditions: FilterNode[] = [];
  for (const row of s.conditions) {
    const value = conditionValue(row);
    if (value !== null) {
      conditions.push(value === undefined ? { field: row.field, op: row.op } : { field: row.field, op: row.op, value });
    }
  }
  return {
    dataset: s.dataset,
    columns: s.columns,
    measures: s.measures.length ? s.measures : undefined,
    filter: conditions.length ? { [s.match]: conditions } : undefined,
    sort: s.sort.length ? s.sort : undefined,
    pageSize: s.pageSize,
    offset,
    calculated: s.calculated.length ? s.calculated : undefined,
    subtotals: s.subtotals && s.measures.length > 0 && s.columns.length > 1 ? true : undefined,
    topN: s.topN && s.measures.length > 0 ? s.topN : undefined,
    having: havingConditions(s),
    pivot: s.pivot && s.measures.length > 0 && s.columns.includes(s.pivot) && s.columns.length > 1 ? s.pivot : undefined,
  };
}

/** Complete conditions on totals of measures that are in the report. */
function havingConditions(s: BuilderState): FilterCondition[] | undefined {
  const keys = s.measures.filter(m => !m.display).map(measureKey);
  const out = s.having
    .filter(h => keys.includes(h.measure) && h.text.trim() !== '' && !Number.isNaN(Number(h.text)))
    .map(h => ({ field: h.measure, op: h.op, value: Number(h.text) }));
  return out.length && s.measures.length > 0 && s.columns.length > 0 ? out : undefined;
}

export const isIncomplete = (row: ConditionRow) => conditionValue(row) === null;

export function toDefinition(s: BuilderState): ReportDefinition {
  const query = toQuery(s);
  delete query.offset;
  return { query, view: s.view };
}

/** Opens a saved definition in the builder (flat AND/OR filter lists; nested groups are flattened to their first level). */
export function fromDefinition(def: ReportDefinition): BuilderState {
  const q = def.query;
  const group = q.filter?.or ? 'or' : 'and';   // no filter, or an AND group: "match all"
  const nodes = q.filter ? (q.filter.and ?? q.filter.or ?? [q.filter]) : [];
  const conditions: ConditionRow[] = nodes
    .filter(n => n.field && n.op)
    .map(n => {
      const v = n.value;
      const list = Array.isArray(v) ? v.map(String) : [];
      return {
        id: newConditionId(),
        field: n.field!,
        op: n.op!,
        text: n.op === 'between' ? (list[0] ?? '') : Array.isArray(v) ? list.join(', ') : v == null ? '' : String(v),
        text2: n.op === 'between' ? (list[1] ?? '') : '',
      };
    });
  return {
    dataset: q.dataset,
    columns: q.columns ?? [],
    measures: q.measures ?? [],
    match: group,
    conditions,
    sort: q.sort ?? [],
    pageSize: q.pageSize ?? 50,
    view: def.view ?? { mode: 'table' },
    calculated: q.calculated ?? [],
    subtotals: q.subtotals ?? false,
    topN: q.topN ?? null,
    having: (q.having ?? []).map(h => ({ id: newConditionId(), measure: h.field, op: h.op as HavingRow['op'], text: String(h.value ?? '') })),
    pivot: q.pivot ?? null,
  };
}

/** Complete filter rows as API conditions (dashboards keep their shared filters in this form). */
export function toConditions(rows: ConditionRow[]): FilterCondition[] {
  const out: FilterCondition[] = [];
  for (const row of rows) {
    const value = conditionValue(row);
    if (value !== null) {
      out.push(value === undefined ? { field: row.field, op: row.op } : { field: row.field, op: row.op, value });
    }
  }
  return out;
}

/** API conditions back into editable filter rows. */
export function fromConditions(list: FilterCondition[]): ConditionRow[] {
  return list.map(n => {
    const v = n.value;
    const values = Array.isArray(v) ? v.map(String) : [];
    return {
      id: newConditionId(),
      field: n.field,
      op: n.op,
      text: n.op === 'between' ? (values[0] ?? '') : Array.isArray(v) ? values.join(', ') : v == null ? '' : String(v),
      text2: n.op === 'between' ? (values[1] ?? '') : '',
    };
  });
}

/** A report's query with extra conditions ANDed to its own filter (dashboard filters, drill-down). */
export function withConditions(q: QueryRequest, extra: FilterCondition[]): QueryRequest {
  if (!extra.length) return q;
  const nodes: FilterNode[] = [...extra];
  if (q.filter) nodes.unshift(q.filter);
  return { ...q, filter: { and: nodes } };
}

/** Conditions selecting one group of an aggregate result (drill-down to its patients). */
export function groupConditions(columns: { key: string; type: string; role: string }[], row: unknown[]): FilterCondition[] {
  const out: FilterCondition[] = [];
  columns.forEach((c, i) => {
    if (c.role !== 'dimension') return;
    const v = row[i];
    if (c.type === 'boolean') {
      out.push({ field: c.key, op: Number(v) === 1 ? 'isTrue' : 'isFalse' });
    } else if (v !== null && v !== undefined) {
      out.push({ field: c.key, op: 'eq', value: String(v) });
    }
  });
  return out;
}
