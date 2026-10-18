import { useEffect, useId, useState } from 'react';
import { api } from '../api';
import { type ConditionRow, OPERATOR_LABELS, RELATIVE_PERIODS, defaultOperator, isIncomplete, sortedOperators } from '../definition';
import type { Field } from '../types';

interface Props {
  row: ConditionRow;
  fields: Field[];
  dataset: string;
  onChange: (row: ConditionRow) => void;
  onRemove: () => void;
}

/** One filter condition: field, operator and a value control that suits both. */
export function FilterRow({ row, fields, dataset, onChange, onRemove }: Props) {
  const field = fields.find(f => f.name === row.field) ?? fields[0];
  const listId = useId();
  const [suggestions, setSuggestions] = useState<string[]>([]);

  // Value suggestions for drop-down style fields: last typed fragment (lists are comma separated).
  const fragment = (row.op === 'in' || row.op === 'notIn' ? row.text.split(',').pop() ?? '' : row.text).trim();
  useEffect(() => {
    if (!field?.suggest || !['eq', 'neq', 'in', 'notIn', 'startsWith', 'contains'].includes(row.op)) {
      setSuggestions([]);
      return;
    }
    const handle = setTimeout(() => {
      api.suggestions(dataset, field.name, fragment)
        .then(values => setSuggestions(values.map(v => String(v.value))))
        .catch(() => setSuggestions([]));
    }, 250);
    return () => clearTimeout(handle);
  }, [dataset, field?.name, field?.suggest, row.op, fragment]);

  if (!field) {
    return null;
  }
  if (!fields.some(f => f.name === row.field)) {
    // a saved filter on a field this user does not have (hidden by the hospital, or another hospital's own field)
    return (
      <div className="filter-row incomplete" role="alert">
        <span>Filter on "{row.field}": this field is not available to you.</span>
        <button type="button" className="icon" aria-label="Remove filter" onClick={onRemove}>✕</button>
      </div>
    );
  }
  const inputType = field.type === 'date' ? 'date' : field.type === 'number' || field.type === 'money' ? 'number' : 'text';
  const set = (patch: Partial<ConditionRow>) => onChange({ ...row, ...patch });

  return (
    <div className={`filter-row${isIncomplete(row) ? ' incomplete' : ''}`}>
      <label className="sr-only" htmlFor={`${listId}-field`}>Field</label>
      <select id={`${listId}-field`} value={row.field}
              onChange={e => {
                const f = fields.find(x => x.name === e.target.value)!;
                set({ field: f.name, op: defaultOperator(f), text: '', text2: '' });
              }}>
        {fields.map(f => <option key={f.name} value={f.name}>{f.label}</option>)}
      </select>

      <label className="sr-only" htmlFor={`${listId}-op`}>Condition</label>
      <select id={`${listId}-op`} value={row.op} onChange={e => set({ op: e.target.value, text: '', text2: '' })}>
        {sortedOperators(field.operators).map(op => <option key={op} value={op}>{OPERATOR_LABELS[op] ?? op}</option>)}
      </select>

      {row.op === 'relative' && (
        <select aria-label="Period" value={row.text} onChange={e => set({ text: e.target.value })}>
          <option value="">Choose period...</option>
          {RELATIVE_PERIODS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
        </select>
      )}

      {row.op === 'between' && (
        <span className="between">
          <input aria-label="From" type={inputType} value={row.text} onChange={e => set({ text: e.target.value })} />
          <span>and</span>
          <input aria-label="To" type={inputType} value={row.text2} onChange={e => set({ text2: e.target.value })} />
        </span>
      )}

      {!['relative', 'between', 'isTrue', 'isFalse'].includes(row.op) && (
        <>
          <input
            aria-label="Value"
            type={row.op === 'in' || row.op === 'notIn' ? 'text' : inputType}
            list={suggestions.length ? `${listId}-values` : undefined}
            placeholder={row.op === 'in' || row.op === 'notIn' ? 'values, separated by commas' : 'value'}
            value={row.text}
            onChange={e => set({ text: e.target.value })}
          />
          {suggestions.length > 0 && (
            <datalist id={`${listId}-values`}>
              {suggestions.map(s => {
                const prefix = row.op === 'in' || row.op === 'notIn'
                  ? row.text.split(',').slice(0, -1).map(x => x.trim()).filter(Boolean).concat(s).join(', ')
                  : s;
                return <option key={s} value={prefix} />;
              })}
            </datalist>
          )}
        </>
      )}

      <button type="button" className="icon" aria-label={`Remove filter on ${field.label}`} onClick={onRemove}>✕</button>
    </div>
  );
}
