// Shapes exchanged with the reporting API (/api/v1). Kept in step with pr-reporting by hand
// (OpenAPI-generated types are planned after the Spring upgrade).

export type FieldType = 'string' | 'number' | 'money' | 'date' | 'boolean';

export interface Field {
  name: string;
  label: string;
  type: FieldType;
  role: 'dimension' | 'measure';
  operators: string[];
  aggregations: string[];
  suggest: boolean;
  /** standard, custom (a hospital's own form field), shared (a hospital's calculated field) or report */
  origin?: 'standard' | 'custom' | 'shared' | 'report';
  formula?: string;
  description?: string;
}

export interface Dataset {
  id: string;
  label: string;
  description: string;
  fields: Field[];
}

export interface MeasureSpec {
  field: string;
  agg?: string;
  /** percentOfTotal, percentOfGroup, runningTotal, rank, change, percentChange */
  display?: string;
}

/** A calculated field of one report (stage 5b). kind and type come from checking the formula. */
export interface CalculatedSpec {
  name: string;
  label: string;
  formula: string;
  kind?: 'column' | 'measure';
  type?: FieldType;
}

export interface SortSpec {
  field: string;
  dir: 'asc' | 'desc';
}

export interface FilterCondition {
  field: string;
  op: string;
  value?: unknown;
}

export interface FilterNode {
  and?: FilterNode[];
  or?: FilterNode[];
  field?: string;
  op?: string;
  value?: unknown;
}

/** What the API runs: never SQL. */
export interface QueryRequest {
  dataset: string;
  columns: string[];
  measures?: MeasureSpec[];
  filter?: FilterNode;
  sort?: SortSpec[];
  pageSize?: number;
  offset?: number;
  calculated?: CalculatedSpec[];
  subtotals?: boolean;
  topN?: number;
  having?: FilterCondition[];
  pivot?: string;
}

export interface OutputColumn {
  key: string;
  label: string;
  type: FieldType;
  role: 'dimension' | 'measure' | 'meta';
  additive?: boolean;
}

export interface QueryResult {
  dataset: string;
  mode: 'detail' | 'aggregate';
  columns: OutputColumn[];
  rows: unknown[][];
  totals: unknown[] | null;
  totalRows: number;
  pageSize: number;
  offset: number;
  dataAsOf: string | null;
  queryMs: number;
  /** with top N: the other groups added up */
  others?: unknown[];
  /** a pivot whose groups did not all fit */
  truncated?: boolean;
}

export type ViewMode = 'table' | 'chart';
export type ChartType = 'bar' | 'line' | 'pie';

export interface ViewSettings {
  mode: ViewMode;
  chart?: ChartType;
  /** conditional formatting: highlight cells of a measure */
  rules?: FormatRule[];
  /** pivot: which measure fills the cells */
  pivotMeasure?: string;
}

export interface FormatRule {
  measure: string;
  op: 'gt' | 'lt' | 'gte' | 'lte' | 'eq';
  value: number;
  color: 'red' | 'amber' | 'green' | 'blue';
}

/** Stored with a saved report. */
export interface ReportDefinition {
  query: QueryRequest;
  view: ViewSettings;
}

export interface SavedReport {
  id: number;
  name: string;
  description?: string;
  template: boolean;
  updatedAt: string;
  definition?: ReportDefinition;
  /** "H001 Main Hospital" for a hospital's report; absent for templates */
  hospital?: string;
  owner?: string;
  /** false: another user's report (only its author or the hospital administrator may change it) */
  canEdit?: boolean;
  visibility?: 'PRIVATE' | 'HOSPITAL';
}

export interface DashboardTile {
  reportId: number;
  title?: string;
  width?: 1 | 2;
  view?: ViewMode;
}

export interface DashboardDefinition {
  tiles: DashboardTile[];
  filters?: FilterCondition[];
}

export interface Dashboard {
  id: number;
  name: string;
  description?: string;
  hospital?: string;
  owner?: string;
  visibility: 'PRIVATE' | 'HOSPITAL';
  canEdit: boolean;
  updatedAt: string;
  definition?: DashboardDefinition;
}

export interface SharedField {
  id: number;
  key: string;
  label: string;
  formula: string;
  description?: string;
  type: FieldType;
  kind: 'column' | 'measure';
  active: boolean;
  hospital?: string;
  owner?: string;
  canEdit: boolean;
}

export interface HospitalRef {
  id: number;
  code: string;
  name: string;
}

/** The signed-in user (GET /api/v1/me). */
export interface Me {
  username: string;
  displayName: string;
  activeHospital: HospitalRef;
  /** hospitals whose data the reports cover */
  analyticsHospitals: HospitalRef[];
  canExport: boolean;
  permissions: string[];
}
