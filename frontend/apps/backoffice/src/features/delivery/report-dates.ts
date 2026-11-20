const LOCALE = 'pt-MZ';

/**
 * The calendar date (YYYY-MM-DD) it is now in the store's timezone: the report's default range. Only a starting
 * point for the filter: which business day an order belongs to is the server's (its cut-off), never derived here.
 */
export function todayIn(timeZone: string | undefined, now: Date): string {
  // en-CA writes dates as YYYY-MM-DD, the contract's ISO date
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
}

/** Why a range cannot be asked for, or undefined when it can (dates are ISO, so they compare as text). */
export function rangeError(from: string, to: string): string | undefined {
  if (!from || !to) return 'Indique as duas datas.';
  if (from > to) return 'A data inicial é posterior à final.';
  return undefined;
}

/** An instant as the store's clock time (FC §12); "—" when the delivery has not reached that step. */
export function timeIn(instant: string | undefined, timeZone: string | undefined): string {
  if (!instant) return '—';
  return new Intl.DateTimeFormat(LOCALE, { timeZone, hour: '2-digit', minute: '2-digit' }).format(new Date(instant));
}
