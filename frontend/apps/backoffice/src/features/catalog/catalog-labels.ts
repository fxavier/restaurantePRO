import { LOCALE, routeChipFill, routeLabel, type StatusMap } from '@restaurantpro/ui';
import { Archive, CircleCheck, CircleHelp, PencilLine, Rocket } from 'lucide-react';
import type { TaxCategorySummary } from './types';

/**
 * The production destinations the screen offers (CONTEXT "Destino de produção"). The contract takes any code
 * (`^[A-Z][A-Z0-9_]*$`); these are the three the design names. Their name and colour are the shared route's
 * (`routeLabel`, `routeChipFill`, DESIGN.md "Light reference"), as on the POS.
 */
export const DESTINATIONS = ['KITCHEN', 'BAR', 'COPA'].map((code) => ({ code, label: routeLabel(code) }));

/**
 * A destination's label and fill: a conventional code by its route's name (COZINHA "Cozinha", as KITCHEN), any other
 * as itself on the neutral fill, none as "Sem destino".
 */
export function destinationOf(code: string | undefined | null): { label: string; fill: string } {
  return { label: code ? routeLabel(code) : 'Sem destino', fill: routeChipFill(code) };
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

/** `ReleaseSummary.status` (§8.3: DRAFT → VALIDATED → PUBLISHED → RETIRED); an unknown one shows as itself. */
const releaseStatuses: StatusMap<string> = {
  DRAFT: { label: 'Rascunho', icon: PencilLine, tone: 'warning' },
  VALIDATED: { label: 'Validada', icon: CircleCheck, tone: 'info' },
  PUBLISHED: { label: 'Publicada', icon: Rocket, tone: 'success' },
  RETIRED: { label: 'Retirada', icon: Archive, tone: 'neutral' },
};

export function releaseStatusesFor(status: string): StatusMap<string> {
  return status in releaseStatuses ? releaseStatuses : { [status]: { label: status, icon: CircleHelp, tone: 'neutral' } };
}

/** "1 aviso", "2 avisos". */
export function warningsLabel(count: number): string {
  return count === 1 ? '1 aviso' : `${count} avisos`;
}

/** `ItemSummary.availability` (ACTIVE, INACTIVE). */
export function availabilityLabel(availability: string | undefined): string {
  return availability === 'INACTIVE' ? 'Inativo' : 'Ativo';
}
