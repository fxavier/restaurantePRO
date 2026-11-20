import { LOCALE } from '@restaurantpro/ui';

const plural = new Intl.PluralRules(LOCALE);

/** "1 pessoa", "4 pessoas". */
export function guestsLabel(guests: number): string {
  return `${guests} ${plural.select(guests) === 'one' ? 'pessoa' : 'pessoas'}`;
}
