import type { components } from '@restaurantpro/api-client';
import { LOCALE, type MoneyValue } from '@restaurantpro/ui';

type Schemas = components['schemas'];
type OrderView = Schemas['com.xavier.backend.orders.model.dto.OrderView'];
type LineView = Schemas['com.xavier.backend.orders.model.dto.LineView'];
type ContractMoney = Schemas['com.xavier.backend.shared.Money'];

export type OrderLine = {
  id: string;
  status: 'DRAFT' | 'CONFIRMED';
  itemId?: string;
  variantId?: string;
  /** A confirmed line's net quantity (what is still billed); a draft's quantity. */
  quantity: number;
  notes?: string;
  version: number;
  /** A confirmed line's snapshot; a draft has none (its name comes from the catalog). */
  description?: string;
  variantName?: string;
  /** The server's gross: a draft's preview (`preview`), absent when its item left the catalog. */
  gross?: MoneyValue;
  preview: boolean;
  components: { groupCode?: string; itemId?: string; variantId?: string; description?: string }[];
};

/** A round (OrderBatch): the drafts confirmed together. */
export type Round = { id: string; batchNo: number; confirmedAt?: string; lines: OrderLine[] };

export type Order = {
  id: string;
  tableId?: string;
  tableCode?: string;
  guests?: number;
  orderNumber?: number;
  lifecycle: 'OPEN' | 'CLOSED' | 'CANCELLED';
  /** The sum of the confirmed lines (D59), as the server computed it. */
  due?: MoneyValue;
  drafts: OrderLine[];
  rounds: Round[];
  /** Confirmed lines outside any round (a transfer's or a split's). */
  unbatched: OrderLine[];
};

/** An answer without its id, lifecycle or lines, or a line without its id, status or version, is an error. */
export function toOrder(view: OrderView | undefined): Order {
  if (!view?.id || !view.lifecycle || !Array.isArray(view.lines)) throw new Error('Incomplete order');
  const lines = view.lines.map((line) => ({ line: toLine(line), batchId: line.batchId }));
  const rounds = (view.batches ?? [])
    .filter((batch) => batch.id && batch.batchNo !== undefined)
    .map((batch) => ({
      id: batch.id!,
      batchNo: batch.batchNo!,
      confirmedAt: batch.confirmedAt,
      lines: lines.filter(({ line, batchId }) => line.status === 'CONFIRMED' && batchId === batch.id).map(({ line }) => line),
    }))
    .sort((a, b) => a.batchNo - b.batchNo);
  const inRounds = new Set(rounds.map((round) => round.id));
  return {
    id: view.id,
    tableId: view.tableId,
    tableCode: view.tableCode,
    guests: view.guests,
    orderNumber: view.orderNumber,
    lifecycle: view.lifecycle,
    due: money(view.due),
    drafts: lines.filter(({ line }) => line.status === 'DRAFT').map(({ line }) => line),
    rounds,
    unbatched: lines
      .filter(({ line, batchId }) => line.status === 'CONFIRMED' && !(batchId && inRounds.has(batchId)))
      .map(({ line }) => line),
  };
}

function toLine(line: LineView): OrderLine {
  if (!line.id || !line.status || line.version === undefined || line.quantity === undefined) {
    throw new Error('Incomplete order');
  }
  return {
    id: line.id,
    status: line.status,
    itemId: line.itemId,
    variantId: line.variantId,
    quantity: line.status === 'CONFIRMED' ? (line.netQuantity ?? line.quantity) : line.quantity,
    notes: line.notes,
    version: line.version,
    description: line.description,
    variantName: line.variantName,
    gross: money(line.gross),
    preview: line.preview ?? line.status === 'DRAFT',
    components: (line.components ?? []).map(({ groupCode, itemId, variantId, description }) => ({
      groupCode,
      itemId,
      variantId,
      description,
    })),
  };
}

function money(value: ContractMoney | undefined): MoneyValue | undefined {
  return value?.amount !== undefined && value.currency ? { amount: value.amount, currency: value.currency } : undefined;
}

/** "2", "0,5": a quantity as the server sent it, in the store's locale. */
export function quantityLabel(quantity: number): string {
  return new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 3 }).format(quantity);
}
