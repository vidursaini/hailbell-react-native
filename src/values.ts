import type { Value } from './types';

/** Text for the native SDKs, which take string maps. Null and undefined entries are left out. */
export function stringMap(values: Record<string, Value | null | undefined> | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  if (!values) return out;
  for (const [key, value] of Object.entries(values)) {
    if (value === null || value === undefined) continue;
    out[key] = String(value);
  }
  return out;
}

/** `undefined` and `''` both mean "not set" to the native SDKs. */
export function optional(value: string | null | undefined): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null;
}
