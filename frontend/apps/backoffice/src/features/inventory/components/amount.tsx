import { Money } from '@restaurantpro/ui';

/** An amount of the store's currency as received; a dash while the currency or the amount is not known. */
export function Amount({ value, currency }: { value: string | undefined; currency: string | undefined }) {
  if (value === undefined || !currency) return <span className="tabular-nums">—</span>;
  return <Money value={{ amount: value, currency }} />;
}
