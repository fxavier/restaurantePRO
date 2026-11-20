import { LOCALE } from '@restaurantpro/ui';

const plural = new Intl.PluralRules(LOCALE);

const counted = (one: string, other: string) => (count: number) =>
  `${count} ${plural.select(count) === 'one' ? one : other}`;

/** "1 pessoa", "4 pessoas". */
export const guestsLabel = counted('pessoa', 'pessoas');

/** "1 reserva", "4 reservas". */
export const reservationsLabel = counted('reserva', 'reservas');
