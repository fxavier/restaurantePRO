import { LOCALE } from '@restaurantpro/ui';

/**
 * Dates and times in the store's zone (FC §12), never the terminal's. A day is `YYYY-MM-DD` (the API's `LocalDate`), a
 * time `HH:MM`, an instant ISO-8601 UTC.
 */

const MINUTE = 60_000;

function wallClock(instant: number, timeZone: string) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(instant);
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((p) => p.type === type)!.value;
  return { date: `${part('year')}-${part('month')}-${part('day')}`, time: `${part('hour')}:${part('minute')}` };
}

/** The store's day at `instant`. */
export function storeDate(instant: Date, timeZone: string): string {
  return wallClock(instant.getTime(), timeZone).date;
}

/** The store's day and clock time at an ISO instant. */
export function storeClock(instant: string, timeZone: string): { date: string; time: string } {
  return wallClock(Date.parse(instant), timeZone);
}

function utcOf(date: string, time = '00:00'): number {
  const [year, month, day] = date.split('-').map(Number) as [number, number, number];
  const [hour, minute] = time.split(':').map(Number) as [number, number];
  return Date.UTC(year, month - 1, day, hour, minute);
}

/** `days` after (or before) a day; calendar arithmetic, so no zone or clock change can skip or repeat one. */
export function addDays(date: string, days: number): string {
  return new Date(utcOf(date) + days * 24 * 60 * MINUTE).toISOString().slice(0, 10);
}

/** The zone's offset from UTC at an instant, in ms. */
function offsetAt(instant: number, timeZone: string): number {
  const { date, time } = wallClock(instant, timeZone);
  return utcOf(date, time) - Math.floor(instant / MINUTE) * MINUTE;
}

/** The instant (ISO UTC) the store's clock shows `time` on `date`; the offset is read again at the result (DST). */
export function storeInstant(date: string, time: string, timeZone: string): string {
  const wall = utcOf(date, time);
  const first = wall - offsetAt(wall, timeZone);
  return new Date(wall - offsetAt(first, timeZone)).toISOString();
}

/** "Sexta-feira, 20 de novembro de 2026". */
export function dayTitle(date: string): string {
  const text = new Intl.DateTimeFormat(LOCALE, {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    timeZone: 'UTC',
  }).format(utcOf(date));
  return text.charAt(0).toUpperCase() + text.slice(1);
}
