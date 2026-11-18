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

/**
 * "12:05": an instant as a clock time in `timeZone`. The POS contract does not give the store's zone yet (gap), so
 * the map passes none and the terminal's own zone applies: a terminal stands in its store.
 */
export function clockTime(instant: string, timeZone?: string): string {
  return new Intl.DateTimeFormat(LOCALE, { hour: '2-digit', minute: '2-digit', timeZone }).format(new Date(instant));
}
