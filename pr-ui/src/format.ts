import type { FieldType } from './types';

const whole = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 });
const decimal = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2 });
const money = new Intl.NumberFormat('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "2026-10-15" -> "15-Oct-2026" (no time-zone conversion: dates are already IST calendar dates). */
export function formatDate(iso: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  return m ? `${m[3]}-${months[Number(m[2]) - 1]}-${m[1]}` : iso;
}

/** "2026-10-15T21:59:00" -> "15-Oct-2026 21:59". */
export function formatDateTime(iso: string | null): string {
  if (!iso) {
    return 'unknown';
  }
  const time = /T(\d{2}:\d{2})/.exec(iso)?.[1] ?? '';
  return `${formatDate(iso)} ${time}`.trim();
}

/** Display value for a grid cell, chart label or summary, by column type (Indian number grouping). */
export function formatValue(value: unknown, type: FieldType, role: 'dimension' | 'measure' | 'meta' = 'dimension'): string {
  if (value === null || value === undefined || value === '') {
    return '';
  }
  if (type === 'boolean' && role === 'dimension') {
    return Number(value) === 1 ? 'Yes' : 'No';
  }
  if (typeof value === 'number' || (typeof value === 'string' && type !== 'string' && type !== 'date' && !isNaN(Number(value)))) {
    const n = Number(value);
    if (type === 'money') {
      return money.format(n);
    }
    return Number.isInteger(n) ? whole.format(n) : decimal.format(n);
  }
  if (type === 'date' && typeof value === 'string') {
    return formatDate(value);
  }
  return String(value);
}

export function isNumeric(type: FieldType, role: 'dimension' | 'measure' | 'meta'): boolean {
  return role === 'measure' || type === 'number' || type === 'money';
}

/** Month buckets (catalogue fields named *_month hold the first day of the month): "2024-10-01" -> "Oct-2024". */
export function formatColumnValue(value: unknown, column: { key: string; type: FieldType; role: 'dimension' | 'measure' | 'meta' }): string {
  if (column.key.endsWith('_month') && typeof value === 'string') {
    const m = /^(\d{4})-(\d{2})/.exec(value);
    if (m) {
      return `${months[Number(m[2]) - 1]}-${m[1]}`;
    }
  }
  return formatValue(value, column.type, column.role);
}
