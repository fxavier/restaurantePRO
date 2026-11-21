import { LOCALE } from '@restaurantpro/ui';

/** A business date (`YYYY-MM-DD`) as the user reads it; a date has no time, so no timezone shifts it. */
export function formatBusinessDate(date: string | undefined): string {
  if (!date || !/^\d{4}-\d{2}-\d{2}$/.test(date)) return date ?? '—';
  return new Intl.DateTimeFormat(LOCALE, { timeZone: 'UTC', day: '2-digit', month: '2-digit', year: 'numeric' }).format(new Date(`${date}T00:00:00Z`));
}

/** An instant in the store's timezone (FC §12); before the timezone is known, the instant is not guessed. */
export function formatInstant(instant: string | undefined, timeZone: string | undefined): string {
  if (!instant || !timeZone) return '—';
  return new Intl.DateTimeFormat(LOCALE, { timeZone, dateStyle: 'short', timeStyle: 'short' }).format(new Date(instant));
}
