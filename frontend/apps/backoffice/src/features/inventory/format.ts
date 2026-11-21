import { LOCALE } from '@restaurantpro/ui';

/**
 * A stock quantity as received (a decimal string, FC §12) with its unit: formatted by Intl from the string itself,
 * never through a float, up to the three decimals the backend keeps. A missing quantity is a dash.
 */
export function formatQuantity(quantity: string | number | undefined, unit: string | undefined): string {
  if (quantity === undefined) return '—';
  const value = (typeof quantity === 'number' ? quantity : quantity.trim()) as Intl.StringNumericLiteral | number;
  const number = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 3 }).format(value);
  return unit ? `${number} ${unit}` : number;
}

const DATE = { day: '2-digit', month: '2-digit', year: 'numeric' } as const;

/** A calendar date as the API sends it (`2026-11-21`, no time, no zone): shown as that same day, never shifted. */
export function formatDate(date: string | undefined): string {
  if (!date) return '—';
  return new Intl.DateTimeFormat(LOCALE, { ...DATE, timeZone: 'UTC' }).format(new Date(`${date}T00:00:00Z`));
}

/** An instant in the store's timezone (§12: the business day can cross midnight). */
export function formatDateTime(instant: string | undefined, timeZone: string | undefined): string {
  if (!instant) return '—';
  return new Intl.DateTimeFormat(LOCALE, { ...DATE, hour: '2-digit', minute: '2-digit', ...(timeZone ? { timeZone } : {}) }).format(
    new Date(instant),
  );
}
