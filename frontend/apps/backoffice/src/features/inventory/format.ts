import { LOCALE } from '@restaurantpro/ui';

/**
 * A stock quantity as received (a decimal string, FC §12) with its unit: formatted by Intl from the string itself,
 * never through a float, up to the three decimals the backend keeps. A missing quantity is a dash.
 */
export function formatQuantity(quantity: string | number | undefined, unit: string | undefined): string {
  if (quantity === undefined) return '—';
  const value = (typeof quantity === 'number' ? quantity : quantity.trim()) as Intl.StringNumericLiteral | number;
  const number = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 3 }).format(value);
  return unit ? `${number} ${unit}` : number;
}
