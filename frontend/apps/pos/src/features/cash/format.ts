import { LOCALE } from '@restaurantpro/ui';

/** "19/11/2026": a business day (`LocalDate`) shown as given, never derived from the clock (FC §12). */
export function businessDay(date: string): string {
  return date.split('-').reverse().join('/');
}

/** "08:30": an instant as a clock time in the store's zone, never the terminal's. */
export function clockTime(instant: string, timeZone: string): string {
  return new Intl.DateTimeFormat(LOCALE, { hour: '2-digit', minute: '2-digit', timeZone }).format(new Date(instant));
}
