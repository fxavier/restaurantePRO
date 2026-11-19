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
  /** A confirmed line's net quantity (what is still billed); a draft's quantity. A decimal string, as the server sent it. */
  quantity: string;
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
  /** The version a command on the order sends back (D17): a stale one is refused with 409. */
  version: number;
  /** The published catalog the drafts' previews come from; absent when there is no draft. */
  catalogVersion?: number;
  /** The promotion release the previews come from; absent when none is published (D104). */
  promotionVersion?: number;
  /** The sum of the confirmed lines (D59), as the server computed it. */
  due?: MoneyValue;
  /**
   * The drafts' provisional total as the server computed it (FE-F27), sent back unchanged as `expectedGross`; absent
   * without drafts or when a draft has no price.
   */
  draftGross?: MoneyValue;
  drafts: OrderLine[];
  rounds: Round[];
  /** Confirmed lines outside any round (a transfer's or a split's). */
  unbatched: OrderLine[];
};

/** An answer without its id, lifecycle, version or lines, or a line without its id, status or version, is an error. */
export function toOrder(view: OrderView | undefined): Order {
  if (!view?.id || !view.lifecycle || view.version === undefined || !Array.isArray(view.lines)) throw new Error('Incomplete order');
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
    version: view.version,
    catalogVersion: view.catalogVersion ?? undefined,
    promotionVersion: view.promotionVersion ?? undefined,
    due: money(view.due),
    draftGross: money(view.draftGross),
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
    // String(): a response stored before decimals became strings (FE-F33) still replays numbers.
    quantity: String(line.status === 'CONFIRMED' ? (line.netQuantity ?? line.quantity) : line.quantity),
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

/** "2", "0,5": a quantity as the server sent it (a decimal string, formatted as one), in the store's locale. */
export function quantityLabel(quantity: string): string {
  return new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 3 }).format(quantity as Intl.StringNumericLiteral);
}

/** UpdateLineRequest: `@Digits(fraction = 3)`, so a quantity is a whole number of thousandths. */
const THOUSANDTHS = 1_000;

/**
 * A draft's quantity stepped by `units` whole units, computed in integer thousandths so 1.3 − 1 is 0.3, not
 * 0.30000000000000004 (which the backend refuses). The result is the decimal string those thousandths name (the
 * contract's type); undefined when it would not be positive (`@Positive`): remove the line instead.
 */
export function stepQuantity(quantity: string, units: number): string | undefined {
  const next = Math.round(Number(quantity) * THOUSANDTHS) + units * THOUSANDTHS;
  return next > 0 ? String(next / THOUSANDTHS) : undefined;
}
