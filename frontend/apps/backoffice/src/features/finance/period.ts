/** The period the finance screen reads: the last `days` business dates up to the store's today, both included. */
export type Period = { from: string; to: string };

const DAY_MS = 86_400_000;

/**
 * The store's calendar date at `now` (`YYYY-MM-DD`), in its timezone, not the browser's: past midnight in Maputo is
 * already the next day there. The business-day cutoff is the server's; this only picks the report's date range.
 */
export function storeToday(now: Date, timeZone: string): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
}

export function lastDays(days: number, now: Date, timeZone: string): Period {
  const to = storeToday(now, timeZone);
  const from = new Date(Date.parse(`${to}T00:00:00Z`) - (days - 1) * DAY_MS).toISOString().slice(0, 10);
  return { from, to };
}
