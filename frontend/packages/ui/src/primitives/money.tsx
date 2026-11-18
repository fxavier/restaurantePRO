import { cn } from '../lib/utils';

/** The contract's Money: `amount` as the endpoint sends it (JSON number or decimal string), `currency` an ISO 4217 code. */
export type MoneyValue = { amount: number | string; currency: string };

export const LOCALE = 'pt-MZ';

/**
 * Formats an amount as received; never computes with it (FC §12). A decimal string goes to Intl as a string, so it is
 * formatted exactly, digit for digit, instead of being rounded through a float.
 */
export function formatMoney({ amount, currency }: MoneyValue, locale: string = LOCALE): string {
  const value = (typeof amount === 'number' ? amount : amount.trim()) as Intl.StringNumericLiteral | number;
  return new Intl.NumberFormat(locale, { style: 'currency', currency }).format(value);
}

export function Money({ value, className }: { value: MoneyValue; className?: string }) {
  return <span className={cn('tabular-nums', className)}>{formatMoney(value)}</span>;
}
