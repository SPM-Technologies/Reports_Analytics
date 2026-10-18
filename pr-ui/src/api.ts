import type { CalculatedSpec, Dashboard, DashboardDefinition, Dataset, FieldType, Me, QueryRequest, QueryResult, ReportDefinition, SavedReport, SharedField } from './types';

/**
 * Context path of the web application. Tomcat serves the UI at <context>/app/, so everything before
 * "/app" is the context; during `npm run dev` (served at /) the default deployment path is used.
 */
export const contextPath: string = (() => {
  const p = window.location.pathname;
  const i = p.indexOf('/app');
  return i >= 0 ? p.substring(0, i) : '/patient-registration';
})();

const API = `${contextPath}/api/v1`;

/** Error carrying the server's problem+json "detail" message, shown to the user as is. */
export class ApiError extends Error {
  constructor(message: string, readonly status = 0) {
    super(message);
  }
}

/** The CSRF token Spring Security keeps in the XSRF-TOKEN cookie; sent back with every change. */
export function csrfToken(): string {
  const m = /(?:^|;\s*)XSRF-TOKEN=([^;]+)/.exec(document.cookie);
  return m ? decodeURIComponent(m[1]) : '';
}

/** Headers for a request: JSON, plus the CSRF token for anything that is not a plain read. */
function headers(method: string | undefined, json: boolean): Record<string, string> {
  const h: Record<string, string> = { Accept: 'application/json' };
  if (json) h['Content-Type'] = 'application/json';
  if (method && method !== 'GET') h['X-XSRF-TOKEN'] = csrfToken();
  return h;
}

async function failure(res: Response): Promise<never> {
  if (res.status === 401) {
    // the session ended (sign-out, timeout): sign in again, then come back here
    window.location.href = `${contextPath}/login`;
    throw new ApiError('Your session has ended. Please sign in again.');
  }
  let message = `Request failed (${res.status})`;
  try {
    const body = await res.json();
    if (body && typeof body.detail === 'string') {
      message = body.detail;
    }
  } catch {
    // not JSON: keep the generic message
  }
  throw new ApiError(message, res.status);
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(API + path, {
    ...init,
    headers: headers(init?.method, Boolean(init?.body)),
    credentials: 'same-origin',
  });
  if (!res.ok) {
    return failure(res);
  }
  return (res.status === 204 ? undefined : await res.json()) as T;
}

export const api = {
  me: () => call<Me>('/me'),

  datasets: () => call<Dataset[]>('/datasets'),

  query: (request: QueryRequest) => call<QueryResult>('/query', { method: 'POST', body: JSON.stringify(request) }),

  suggestions: (dataset: string, field: string, q: string) =>
    call<{ value: unknown; count: number }[]>(
      `/datasets/${encodeURIComponent(dataset)}/fields/${encodeURIComponent(field)}/values?q=${encodeURIComponent(q)}`,
    ),

  reports: () => call<SavedReport[]>('/reports'),
  report: (id: number) => call<SavedReport>(`/reports/${id}`),
  createReport: (name: string, description: string, definition: ReportDefinition, visibility?: string) =>
    call<SavedReport>('/reports', { method: 'POST', body: JSON.stringify({ name, description, definition, visibility }) }),
  updateReport: (id: number, name: string, description: string, definition: ReportDefinition, visibility?: string) =>
    call<SavedReport>(`/reports/${id}`, { method: 'PUT', body: JSON.stringify({ name, description, definition, visibility }) }),

  /** Checks a formula; the error message names the mistake and its position. */
  validateFormula: (dataset: string, formula: string, calculated: CalculatedSpec[]) =>
    call<{ kind: 'column' | 'measure'; type: FieldType; formula: string }>('/formulas/validate',
      { method: 'POST', body: JSON.stringify({ dataset, formula, calculated }) }),
  sharedFields: (dataset: string) => call<SharedField[]>(`/calculated-fields?dataset=${encodeURIComponent(dataset)}`),
  createSharedField: (dataset: string, label: string, formula: string, description: string) =>
    call<SharedField>('/calculated-fields', { method: 'POST', body: JSON.stringify({ dataset, label, formula, description }) }),
  updateSharedField: (id: number, label: string, formula: string, description: string, active: boolean) =>
    call<SharedField>(`/calculated-fields/${id}`, { method: 'PUT', body: JSON.stringify({ label, formula, description, active }) }),

  dashboards: () => call<Dashboard[]>('/dashboards'),
  dashboard: (id: number) => call<Dashboard>(`/dashboards/${id}`),
  createDashboard: (name: string, definition: DashboardDefinition, visibility: string) =>
    call<Dashboard>('/dashboards', { method: 'POST', body: JSON.stringify({ name, definition, visibility }) }),
  updateDashboard: (id: number, name: string, definition: DashboardDefinition, visibility: string) =>
    call<Dashboard>(`/dashboards/${id}`, { method: 'PUT', body: JSON.stringify({ name, definition, visibility }) }),
  deleteDashboard: (id: number) => call<void>(`/dashboards/${id}`, { method: 'DELETE' }),
  deleteReport: (id: number) => call<void>(`/reports/${id}`, { method: 'DELETE' }),

  /** Downloads the report as a file; errors (e.g. too many rows for PDF) are thrown as ApiError. */
  async download(query: QueryRequest, format: 'xlsx' | 'pdf' | 'csv', title: string): Promise<void> {
    const res = await fetch(`${API}/query/export`, {
      method: 'POST',
      headers: headers('POST', true),
      credentials: 'same-origin',
      body: JSON.stringify({ query: { ...query, offset: undefined }, format, title }),
    });
    if (!res.ok) {
      return failure(res);
    }
    const disposition = res.headers.get('Content-Disposition') ?? '';
    const name = /filename="([^"]+)"/.exec(disposition)?.[1] ?? `report.${format}`;
    const url = URL.createObjectURL(await res.blob());
    const link = document.createElement('a');
    link.href = url;
    link.download = name;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  },
};
