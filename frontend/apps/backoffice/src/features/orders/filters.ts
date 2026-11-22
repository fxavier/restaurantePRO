import type { OrderFilter } from './hooks/use-orders';

/** Why a period cannot be asked for, or undefined when it can; either bound may be absent (ISO dates compare as text). */
export function rangeError({ from, to }: OrderFilter): string | undefined {
  return from && to && from > to ? 'A data inicial é posterior à final.' : undefined;
}

/** The filter with one field set from a form control: an empty or blank value means "no filter", so it is not sent. */
export function withField<K extends keyof OrderFilter>(filter: OrderFilter, key: K, value: string): OrderFilter {
  const next = { ...filter };
  const trimmed = value.trim();
  if (trimmed) next[key] = trimmed as OrderFilter[K];
  else delete next[key];
  return next;
}
