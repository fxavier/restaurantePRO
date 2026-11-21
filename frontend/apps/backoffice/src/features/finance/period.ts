/** The period the finance screen reads: business dates `from` to `to`, both included, in the store's `timeZone`. */
export type Period = { from: string; to: string; timeZone: string };

const DAY_MS = 86_400_000;

/**
 * The store's calendar date at `now` (`YYYY-MM-DD`), in its timezone, not the browser's: past midnight in Maputo is
 * already the next day there. The business-day cutoff is the server's; this only picks the report's date range.
 */
export function storeToday(now: Date, timeZone: string): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
}

function addDays(date: string, days: number): string {
  return new Date(Date.parse(`${date}T00:00:00Z`) + days * DAY_MS).toISOString().slice(0, 10);
}

/** The last `days` dates up to the store's today. */
export function lastDays(days: number, now: Date, timeZone: string): Period {
  const to = storeToday(now, timeZone);
  return { from: addDays(to, -(days - 1)), to, timeZone };
}

/** The dates of the period, as the reports and the cash reconciliation take them. */
export function dates({ from, to }: Period) {
  return { from, to };
}

/** The UTC offset of `timeZone` on `date` (`+02:00`), read at noon so a change at night does not move the day. */
export function offsetOn(date: string, timeZone: string): string {
  const name = new Intl.DateTimeFormat('en-US', { timeZone, timeZoneName: 'longOffset' })
    .formatToParts(new Date(`${date}T12:00:00Z`))
    .find((part) => part.type === 'timeZoneName')?.value;
  const offset = name?.replace('GMT', '');
  return offset ? offset : '+00:00';
}

/**
 * The period as instants, for the filters that take them (documents, audit): from the store's midnight that starts
 * `from` to the one that ends `to`.
 */
export function instants({ from, to, timeZone }: Period) {
  const end = addDays(to, 1);
  return { from: `${from}T00:00:00${offsetOn(from, timeZone)}`, to: `${end}T00:00:00${offsetOn(end, timeZone)}` };
}
