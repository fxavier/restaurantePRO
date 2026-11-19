import type { components } from '@restaurantpro/api-client';
import { LOCALE, type MoneyValue } from '@restaurantpro/ui';

type Schemas = components['schemas'];
type OrderView = Schemas['com.xavier.backend.orders.model.dto.OrderView'];
type LineView = Schemas['com.xavier.backend.orders.model.dto.LineView'];

export type OrderLine = {
  id: string;
  status: 'DRAFT' | 'CONFIRMED';
  itemId: string;
  variantId: string | null;
  /** A confirmed line's net quantity (what is still billed); a draft's quantity. A decimal string, as the server sent it. */
  quantity: string;
  notes: string | null;
  version: number;
  /** A confirmed line's snapshot; a draft has none (its name comes from the catalog). */
  description: string | null;
  variantName: string | null;
  /** The server's gross: a draft's preview (`preview`), null when its item left the catalog. */
  gross: MoneyValue | null;
  preview: boolean;
  components: { groupCode: string; itemId: string; variantId: string | null; description: string | null }[];
};

/** A round (OrderBatch): the drafts confirmed together. */
export type Round = { id: string; batchNo: number; confirmedAt: string; lines: OrderLine[] };

export type Order = {
  id: string;
  tableId: string | null;
  tableCode: string | null;
  guests: number | null;
  orderNumber: number;
  lifecycle: 'OPEN' | 'CLOSED' | 'CANCELLED';
  /** The version a command on the order sends back (D17): a stale one is refused with 409. */
  version: number;
  /** The published catalog the drafts' previews come from; null when there is no draft. */
  catalogVersion: number | null;
  /** The promotion release the previews come from; null when none is published (D104). */
  promotionVersion: number | null;
  /** The sum of the confirmed lines (D59), as the server computed it. */
  due: MoneyValue;
  /**
   * The drafts' provisional total as the server computed it (FE-F27), sent back unchanged as `expectedGross`; null
   * without drafts or when a draft has no price.
   */
  draftGross: MoneyValue | null;
  drafts: OrderLine[];
  rounds: Round[];
  /** Confirmed lines outside any round (a transfer's or a split's). */
  unbatched: OrderLine[];
};

/** The contract marks what an order always carries (FE-F28); only a missing answer is an error here. */
export function toOrder(view: OrderView | undefined): Order {
  if (!view) throw new Error('Incomplete order');
  const lines = view.lines.map((line) => ({ line: toLine(line), batchId: line.batchId }));
  const rounds = view.batches
    .map((batch) => ({
      id: batch.id,
      batchNo: batch.batchNo,
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
    catalogVersion: view.catalogVersion,
    promotionVersion: view.promotionVersion,
    due: view.due,
    draftGross: view.draftGross,
    drafts: lines.filter(({ line }) => line.status === 'DRAFT').map(({ line }) => line),
    rounds,
    unbatched: lines
      .filter(({ line, batchId }) => line.status === 'CONFIRMED' && !(batchId && inRounds.has(batchId)))
      .map(({ line }) => line),
  };
}

function toLine(line: LineView): OrderLine {
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
    gross: line.gross,
    preview: line.preview,
    components: line.components.map(({ groupCode, itemId, variantId, description }) => ({
      groupCode,
      itemId,
      variantId,
      description,
    })),
  };
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

/** A decimal as canonical text: no sign on zero, no leading zeros in the integer part, no trailing fraction zeros. */
function canonicalDecimal(value: string | number): string {
  const text = String(value).trim();
  const negative = text.startsWith('-');
  const [integer = '', fraction = ''] = text.replace(/^[+-]/, '').split('.');
  const whole = integer.replace(/^0+(?=\d)/, '') || '0';
  const decimals = fraction.replace(/0+$/, '');
  const digits = decimals ? `${whole}.${decimals}` : whole;
  return negative && digits !== '0' ? `-${digits}` : digits;
}

/**
 * The same decimal amount, compared exactly as text: "120.00" and 120 are one price, and two amounts that differ
 * beyond float precision stay different (amounts arrive as decimal strings, FE-F33; never Number()).
 */
export function sameAmount(a: string | number, b: string | number): boolean {
  return canonicalDecimal(a) === canonicalDecimal(b);
}
