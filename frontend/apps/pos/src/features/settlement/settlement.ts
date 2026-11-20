import type { components } from '@restaurantpro/api-client';
import type { MoneyValue } from '@restaurantpro/ui';

type Schemas = components['schemas'];
type SettlementView = Schemas['com.xavier.backend.settlement.model.dto.OrderSettlementView'];
type PartitionView = Schemas['com.xavier.backend.settlement.model.dto.PartitionView'];
type LineCoverage = Schemas['com.xavier.backend.settlement.model.dto.LineCoverage'];

/** A part of the bill (BillPartition, D67): by items, or an amount (an equal part, with its number). */
export type Partition = {
  id: string;
  label: string;
  kind: 'ITEMS' | 'AMOUNT';
  version: number;
  due: MoneyValue;
  balance: MoneyValue;
  paymentStatus: string;
  lines: { lineId: string; description: string; quantity: string }[];
};

/**
 * An order's bill as settlement has it (`GET /orders/{id}/settlement`): every amount and state is the server's, read
 * again after every command; nothing here is computed from them.
 */
export type Settlement = {
  orderId: string;
  lifecycle: string;
  orderVersion: number;
  due: MoneyValue;
  paid: MoneyValue;
  balance: MoneyValue;
  /** What the order itself (outside its live parts) still has to collect (D69). */
  residualBalance: MoneyValue;
  /** The live parts, oldest first; a cancelled one is history, not shown. */
  partitions: Partition[];
  /** The billable lines with a quantity in no live part yet, which a split by items may take. */
  undivided: UndividedLine[];
};

/** A line's quantity still in no part, in thousandths (the contract's 3 decimals), so stepping it is exact. */
export type UndividedLine = { lineId: string; description: string; available: number };

/** The contract marks no field of the view required; a bill without these cannot be shown or acted on. */
export function toSettlement(view: SettlementView | undefined): Settlement {
  if (!view?.orderId || !view.lifecycle || view.orderVersion === undefined || !view.due || !view.paid || !view.balance) {
    throw new Error('Incomplete settlement');
  }
  return {
    orderId: view.orderId,
    lifecycle: view.lifecycle,
    orderVersion: view.orderVersion,
    due: view.due,
    paid: view.paid,
    balance: view.balance,
    residualBalance: view.residualBalance ?? view.balance,
    partitions: (view.partitions ?? []).filter((partition) => partition.status === 'LIVE').map(toPartition),
    undivided: (view.coverage ?? []).flatMap(toUndivided),
  };
}

function toUndivided(line: LineCoverage): UndividedLine[] {
  if (!line.lineId || line.netQty === undefined) return [];
  const available = thousandths(line.netQty) - thousandths(line.partitioned ?? '0');
  return available > 0 ? [{ lineId: line.lineId, description: line.description ?? '', available }] : [];
}

/** What a payment pays (D69): a live part, or the order's residual (the whole bill when it is not split). */
export type Target = { key: string; label: string; balance: MoneyValue; partitionId?: string };

/** The selected part, while it is live; otherwise the order's residual. */
export function targetOf(settlement: Settlement, partitionId: string | undefined): Target {
  const partition = settlement.partitions.find((candidate) => candidate.id === partitionId);
  if (partition) return { key: partition.id, label: partition.label, balance: partition.balance, partitionId: partition.id };
  return {
    key: 'order',
    label: settlement.partitions.length > 0 ? 'Restante da conta' : 'Conta total',
    balance: settlement.residualBalance,
  };
}

/**
 * An amount as typed ("12,5", "1530.00") as the decimal string the contract takes, or undefined when it is not a
 * positive amount with at most two decimals. Text in, text out: nothing is computed with it.
 */
export function amountInput(text: string): string | undefined {
  const decimal = text.trim().replace(',', '.');
  return /^\d+(\.\d{1,2})?$/.test(decimal) && /[1-9]/.test(decimal) ? decimal : undefined;
}

/** One unit, in thousandths. */
export const UNIT = 1_000;

/** A decimal quantity (3 decimals at most, `@Digits(fraction = 3)`) as whole thousandths. */
export function thousandths(quantity: string | number): number {
  return Math.round(Number(quantity) * UNIT);
}

/** Whole thousandths as the decimal string the contract takes ("2", "0.5"). */
export function quantityText(value: number): string {
  return String(value / UNIT);
}

function toPartition(view: PartitionView): Partition {
  if (!view.id || !view.due || !view.balance || view.version === undefined || !isKind(view.kind)) {
    throw new Error('Incomplete partition');
  }
  return {
    id: view.id,
    label: partitionLabel(view),
    kind: view.kind,
    version: view.version,
    due: view.due,
    balance: view.balance,
    paymentStatus: view.paymentStatus ?? 'UNPAID',
    lines: (view.lines ?? []).map((line) => ({
      lineId: line.lineId ?? '',
      description: line.description ?? '',
      quantity: String(line.quantity ?? ''),
    })),
  };
}

const isKind = (kind: string | undefined): kind is Partition['kind'] => kind === 'ITEMS' || kind === 'AMOUNT';

/** "Parte 2 de 3" for an equal part; otherwise its label, or what it holds. */
function partitionLabel({ label, kind, partNo, parts }: PartitionView): string {
  if (partNo && parts) return `Parte ${partNo} de ${parts}`;
  if (label) return label;
  return kind === 'ITEMS' ? 'Por artigos' : 'Por valor';
}
