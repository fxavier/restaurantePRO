import { LOCALE, type StatusMap } from '@restaurantpro/ui';
import { CircleCheck, CircleSlash } from 'lucide-react';

/**
 * A business day (`LocalDate`, "2026-11-02") as the store reads it, "02/11/2026": shown as given, never through a
 * `Date`, which would move it by the browser's timezone (FC §12). Anything else is shown unchanged.
 */
export function formatBusinessDate(day: string | undefined): string {
  if (!day) return '';
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day);
  return match ? `${match[3]}/${match[2]}/${match[1]}` : day;
}

const ledgerKinds: Record<string, string> = { DEBIT: 'Débito', CREDIT: 'Crédito' };

/** A current-account movement's kind (backend `EntryKind`); an unknown one is shown as sent. */
export function ledgerKindLabel(kind: string | undefined): string {
  return (kind && ledgerKinds[kind]) ?? kind ?? '';
}

const loyaltyKinds: Record<string, string> = {
  EARN: 'Acumulação',
  REDEEM: 'Resgate',
  EARN_REVERSAL: 'Anulação de acumulação',
  REDEEM_REVERSAL: 'Anulação de resgate',
  ADJUSTMENT: 'Ajuste manual',
  EXPIRY: 'Expiração',
};

/** A loyalty movement's kind (backend `promotions.loyalty.model.EntryKind`); an unknown one is shown as sent. */
export function loyaltyKindLabel(kind: string | undefined): string {
  return (kind && loyaltyKinds[kind]) ?? kind ?? '';
}

const signed = new Intl.NumberFormat(LOCALE, { signDisplay: 'exceptZero' });
const plain = new Intl.NumberFormat(LOCALE);

/** A movement of points, with its sign ("+300", "−20"). */
export function formatPointsChange(points: number | undefined): string {
  return points === undefined ? '' : signed.format(points);
}

const addressKinds: Record<string, string> = { BILLING: 'Faturação', DELIVERY: 'Entrega' };

/** An address's kind (AddressRequest.kind); an unknown one is shown as sent. */
export function addressKindLabel(kind: string | undefined): string {
  return (kind && addressKinds[kind]) ?? kind ?? '';
}

/** A balance of points ("1 320"). */
export function formatPoints(points: number | undefined): string {
  return points === undefined ? '' : plain.format(points);
}

/** A customer is active or not (`active === false` is inactive): a tinted pill with its icon and word. */
export const customerStatuses: StatusMap<'ACTIVE' | 'INACTIVE'> = {
  ACTIVE: { label: 'Ativo', icon: CircleCheck, tone: 'success' },
  INACTIVE: { label: 'Inativo', icon: CircleSlash, tone: 'neutral' },
};

export function customerStatus(customer: { active?: boolean }): 'ACTIVE' | 'INACTIVE' {
  return customer.active === false ? 'INACTIVE' : 'ACTIVE';
}
