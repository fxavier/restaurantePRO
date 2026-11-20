import { LOCALE } from '@restaurantpro/ui';
import type { TaxCategorySummary } from './types';

/** A destination without a route colour: the raised surface with light text. */
const NEUTRAL = 'bg-muted text-foreground';

/**
 * The production destinations the screen offers (CONTEXT "Destino de produção"). The contract takes any code
 * (`^[A-Z][A-Z0-9_]*$`); these are the three the design names, each with its route colour as a fill with dark text.
 */
export const DESTINATIONS = [
  { code: 'KITCHEN', label: 'Cozinha', fill: 'bg-route-kitchen text-background' },
  { code: 'BAR', label: 'Bar', fill: 'bg-route-bar text-background' },
  { code: 'COPA', label: 'Copa', fill: 'bg-route-copa text-background' },
] as const;

/** A destination's label and fill; an unknown code shows as itself on a neutral fill, none as "Sem destino". */
export function destinationOf(code: string | undefined | null): { label: string; fill: string } {
  if (!code) return { label: 'Sem destino', fill: NEUTRAL };
  return DESTINATIONS.find((destination) => destination.code === code) ?? { label: code, fill: NEUTRAL };
}

/** The rate in force at `at` (ISO-8601 UTC): the latest one already effective; none when every rate is future. */
export function currentRate(category: TaxCategorySummary, at: string): string | undefined {
  let current: { effectiveFrom: string; ratePercent: string } | undefined;
  for (const rate of category.rates ?? []) {
    if (!rate.effectiveFrom || rate.ratePercent === undefined || rate.effectiveFrom > at) continue;
    if (!current || rate.effectiveFrom > current.effectiveFrom) current = { effectiveFrom: rate.effectiveFrom, ratePercent: rate.ratePercent };
  }
  return current?.ratePercent;
}

const percent = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 2 });

/** "IVA normal (16%)": the category with its rate in force, formatted from the decimal string, never computed. */
export function taxLabel(category: TaxCategorySummary | undefined, at: string): string {
  if (!category) return '—';
  const rate = currentRate(category, at);
  return rate === undefined
    ? (category.name ?? '')
    : `${category.name} (${percent.format(rate as Intl.StringNumericLiteral)}%)`;
}

/** `ItemSummary.availability` (ACTIVE, INACTIVE). */
export function availabilityLabel(availability: string | undefined): string {
  return availability === 'INACTIVE' ? 'Inativo' : 'Ativo';
}
