import { LOCALE } from '@restaurantpro/ui';

const plural = new Intl.PluralRules(LOCALE);

/** "1 pessoa", "3 pessoas". */
export function guestsLabel(guests: number): string {
  return `${guests} ${plural.select(guests) === 'one' ? 'pessoa' : 'pessoas'}`;
}

/** "1 lugar", "4 lugares". */
export function seatsLabel(seats: number): string {
  return `${seats} ${plural.select(seats) === 'one' ? 'lugar' : 'lugares'}`;
}

/** Whole minutes from `instant` to `now`; a terminal clock behind the server's shows 0, not less. */
export function minutesSince(instant: string, now: Date): number {
  return Math.max(0, Math.floor((now.getTime() - Date.parse(instant)) / 60_000));
}

/** "32 min", "1 h 05 min": how long a table has been open. */
export function elapsedLabel(minutes: number): string {
  if (minutes < 60) return `${minutes} min`;
  return `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, '0')} min`;
}

/** "Mesa ou nº": a table whose code contains the typed text, ignoring case and the spaces around it. */
export function matchesTable(code: string, query: string): boolean {
  return code.toLowerCase().includes(query.trim().toLowerCase());
}

/** "12:05": an instant as a clock time in `timeZone`, the store's (`useStoreTimeZone`), never the terminal's. */
export function clockTime(instant: string, timeZone: string): string {
  return new Intl.DateTimeFormat(LOCALE, { hour: '2-digit', minute: '2-digit', timeZone }).format(new Date(instant));
}
