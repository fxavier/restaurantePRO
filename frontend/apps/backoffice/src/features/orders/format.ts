import { LOCALE } from '@restaurantpro/ui';
import type { LineView } from './types';

/** What a line sold, as the server describes it: the item, then its variant; the item code when it has no name. */
export function lineName({ description, variantName, itemCode }: Pick<LineView, 'description' | 'variantName' | 'itemCode'>): string {
  const name = description ?? itemCode ?? 'Artigo';
  return variantName ? `${name} · ${variantName}` : name;
}

/** A business date (`YYYY-MM-DD`) as dd/mm/yyyy: a date has no time, so no timezone may shift it. */
export function businessDate(date: string): string {
  const [year, month, day] = date.split('-');
  return `${day}/${month}/${year}`;
}

/**
 * An instant on the store's clock (FC §12), as a time or with its date too; "—" when there is no instant yet, or
 * before the store's timezone is known (never guessed).
 */
export function storeTime(instant: string | null | undefined, timeZone: string | undefined, withDate = false): string {
  if (!instant || !timeZone) return '—';
  const date = withDate ? ({ day: '2-digit', month: '2-digit', year: 'numeric' } as const) : {};
  return new Intl.DateTimeFormat(LOCALE, { timeZone, hour: '2-digit', minute: '2-digit', ...date }).format(new Date(instant));
}
