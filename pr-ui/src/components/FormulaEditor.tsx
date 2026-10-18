import { useRef, useState } from 'react';
import { ApiError, api } from '../api';
import type { CalculatedSpec, Dataset } from '../types';

/** The functions of the formula language (server: reporting.formula.FormulaCompiler), for the help list. */
const FUNCTIONS: [string, string, string][] = [
  ['Totals', 'COUNT()', 'number of patients'],
  ['Totals', 'COUNT_IF(condition)', 'patients for whom the condition is true'],
  ['Totals', 'COUNT_DISTINCT(field)', 'number of different values'],
  ['Totals', 'SUM(x)  AVG(x)  MIN(x)  MAX(x)', 'total, average, lowest, highest'],
  ['Totals', 'SUM_IF(x, condition)  AVG_IF(x, condition)', 'total / average where the condition is true'],
  ['Totals', 'MEDIAN(x)  PERCENTILE(x, 0.9)  STDDEV(x)', 'middle value, percentile, spread'],
  ['Logic', 'IF(condition, then, else)', 'one of two values'],
  ['Logic', 'SWITCH(x, "a", r1, "b", r2, otherwise)', 'map values to results (e.g. cities to regions)'],
  ['Logic', 'BUCKET(x, 18, 40, 60)', 'ranges: "below 18", "18 to 39", ...'],
  ['Logic', 'IN(x, "a", "b")  AND  OR  NOT', 'conditions'],
  ['Numbers', 'ROUND(x, 1)  ABS(x)  NUMBER(text)', 'rounding, absolute value, text to number'],
  ['Text', 'CONCAT(a, b, ...)  a & b  TEXT(x)', 'join text'],
  ['Text', 'UPPER(t)  LOWER(t)  TRIM(t)  LEN(t)  LEFT(t, n)  RIGHT(t, n)', 'text functions'],
  ['Text', 'CONTAINS(t, "x")  STARTS_WITH(t, "x")', 'text conditions'],
  ['Dates', 'TODAY()  DATE("2026-01-01")', 'today, a fixed date'],
  ['Dates', 'YEAR(d)  QUARTER(d)  MONTH(d)  WEEK(d)  DAY(d)  WEEKDAY(d)', 'parts of a date'],
  ['Dates', 'MONTH_START(d)  QUARTER_START(d)  YEAR_START(d)  WEEK_START(d)', 'group dates'],
  ['Dates', 'DAYS_BETWEEN(a, b)  MONTHS_BETWEEN(a, b)  YEARS_BETWEEN(a, b)', 'differences'],
];

interface Props {
  dataset: Dataset;
  /** the report's calculated fields defined before this one (they can be used in the formula) */
  earlier: CalculatedSpec[];
  initial: CalculatedSpec | null;
  canShare: boolean;
  onSave: (spec: CalculatedSpec, share: boolean, description: string) => Promise<void>;
  onCancel: () => void;
}

/**
 * Editor for a calculated field: name, formula, fields and functions to insert, and a check against the server
 * (which also decides whether the formula is a per-patient column or a total). Saving needs a successful check.
 */
/**
 * Formulas are stored with field names ([age_years]) so they work for every viewer and after a field is
 * renamed; for editing they are shown with the labels this user sees ([Age]).
 */
export function withLabels(formula: string, ds: Dataset, earlier: CalculatedSpec[]): string {
  const labels = new Map<string, string>();
  ds.fields.forEach(f => labels.set(f.name, f.label));
  earlier.forEach(c => labels.set(c.name, c.label));
  return formula.replace(/\[([a-z][a-z0-9_]*)\]/g, (whole, name: string) => (labels.has(name) ? `[${labels.get(name)}]` : whole));
}

export function FormulaEditor({ dataset, earlier, initial, canShare, onSave, onCancel }: Props) {
  const [label, setLabel] = useState(initial?.label ?? '');
  const [formula, setFormula] = useState(initial?.formula ? withLabels(initial.formula, dataset, earlier) : '');
  const [description, setDescription] = useState('');
  const [share, setShare] = useState(false);
  const [checked, setChecked] = useState<{ kind: 'column' | 'measure'; type: CalculatedSpec['type']; formula: string } | null>(
    initial?.kind ? { kind: initial.kind, type: initial.type, formula: initial.formula } : null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const area = useRef<HTMLTextAreaElement>(null);

  const insert = (text: string) => {
    const el = area.current;
    const start = el?.selectionStart ?? formula.length;
    const end = el?.selectionEnd ?? formula.length;
    const next = formula.slice(0, start) + text + formula.slice(end);
    setFormula(next);
    setChecked(null);
    requestAnimationFrame(() => {
      el?.focus();
      el?.setSelectionRange(start + text.length, start + text.length);
    });
  };

  const check = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await api.validateFormula(dataset.id, formula, earlier);
      setChecked({ kind: r.kind, type: r.type, formula: r.formula });
    } catch (e) {
      setChecked(null);
      setError(e instanceof ApiError ? e.message : 'Could not check the formula.');
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    if (!checked || !label.trim()) return;
    const name = initial?.name ?? `calc_${label.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 30) || 'field'}_${Date.now() % 100000}`;
    setBusy(true);
    try {
      await onSave({ name, label: label.trim(), formula: checked.formula, kind: checked.kind, type: checked.type }, share, description);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not save.');
    } finally {
      setBusy(false);
    }
  };

  const fields = dataset.fields.filter(f => f.name !== 'patient_id');
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label="Calculated field">
      <div className="modal">
        <h2>{initial ? 'Edit calculated field' : 'New calculated field'}</h2>
        <label className="stack">Name
          <input value={label} maxLength={60} onChange={e => setLabel(e.target.value)} placeholder="e.g. Insured %" autoFocus />
        </label>
        <label className="stack">Formula
          <textarea ref={area} value={formula} rows={4} maxLength={2000} spellCheck={false}
                    onChange={e => { setFormula(e.target.value); setChecked(null); }}
                    placeholder='e.g. ROUND(COUNT_IF([Insured]) / COUNT() * 100, 1)   or   IF([Age] >= 60, "Senior", "Adult")' />
        </label>
        <div className="insert-row">
          <select aria-label="Insert a field" value="" onChange={e => { if (e.target.value) insert(`[${e.target.value}]`); }}>
            <option value="">+ Insert a field...</option>
            {fields.map(f => <option key={f.name} value={f.label}>{f.label}{f.role === 'measure' ? ' (total)' : ''}</option>)}
          </select>
          <button type="button" onClick={check} disabled={busy || !formula.trim()}>Check</button>
        </div>
        {error && <div className="alert error" role="alert">{error}</div>}
        {checked && !error && (
          <div className="alert ok" role="status">
            Valid: {checked.kind === 'measure' ? 'a total (use it under Summarise)' : 'a value per patient (use it as a column, group or filter)'} of type {checked.type}.
          </div>
        )}
        {canShare && !initial && (
          <>
            <label className="check"><input type="checkbox" checked={share} onChange={e => setShare(e.target.checked)} /> Share with everyone in my hospital</label>
            {share && <label className="stack">Description (optional)
              <input value={description} maxLength={200} onChange={e => setDescription(e.target.value)} /></label>}
          </>
        )}
        <details className="functions">
          <summary>Functions</summary>
          <table>
            <tbody>
              {FUNCTIONS.map(([group, fn, help]) => (
                <tr key={fn}><td className="muted">{group}</td><td><code>{fn}</code></td><td>{help}</td></tr>
              ))}
            </tbody>
          </table>
          <p className="hint">Fields are written as [Field name]. Text in "double quotes". Formulas only see fields you may see.</p>
        </details>
        <div className="actions end">
          <button type="button" onClick={onCancel}>Cancel</button>
          <button type="button" className="primary" onClick={save} disabled={busy || !checked || !label.trim()}>Save</button>
        </div>
      </div>
    </div>
  );
}
