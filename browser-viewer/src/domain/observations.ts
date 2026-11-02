import type { InspectionState, ObjectLocator, RawValue } from './inspection.js';
export type Observation<T> = { state: 'present'; value: NonNullable<T>; basis: 'observed' | 'derived' | 'inferred'; raw: readonly RawValue[]; evidence: readonly ObjectLocator[]; reason: string | null; candidates: readonly NonNullable<T>[] }
  | { state: Exclude<InspectionState, 'present'>; value: null; basis: 'none'; raw: readonly RawValue[]; evidence: readonly ObjectLocator[]; reason: string; candidates: readonly NonNullable<T>[] };
export function observation<T = string>(value: T | null = null, options: { state?: InspectionState; basis?: 'observed' | 'derived' | 'inferred'; raw?: readonly RawValue[]; evidence?: readonly ObjectLocator[]; reason?: string; candidates?: readonly NonNullable<T>[] } = {}): Observation<T> {
  const state = options.state ?? (value == null ? 'missing' : 'present');
  const common = { raw: options.raw ?? [], evidence: options.evidence ?? [], candidates: options.candidates ?? [] };
  return state === 'present' && value != null ? { ...common, state, value, basis: options.basis ?? 'observed', reason: options.reason ?? null }
    : { ...common, state: state === 'present' ? 'unavailable' : state, value: null, basis: 'none', reason: options.reason ?? 'No value was observed.' };
}
export const links = (ids: readonly string[], evidence: ObjectLocator[], unique = false, reason = 'Exact identity or byte matching.'): Observation<readonly string[]> =>
  unique && new Set(ids).size > 1 ? observation(null, { state: 'ambiguous', evidence, reason, candidates: [...new Set(ids)].sort().map(id => [id]) })
    : ids.length ? observation([...new Set(ids)].sort(), { basis: 'derived', evidence, reason }) : observation(null, { state: 'unavailable', evidence, reason });
export type Mutable<T> = { -readonly [P in keyof T]: T[P] };
