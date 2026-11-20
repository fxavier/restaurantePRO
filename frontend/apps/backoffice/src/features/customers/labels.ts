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
