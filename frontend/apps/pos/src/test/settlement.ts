import type { components } from '@restaurantpro/api-client';
import { HttpResponse } from 'msw';
import { ORDER_ID } from './order';
import { queue, recording, SESSION } from './server';
import { id } from './table-map';

type Schemas = components['schemas'];
export type SettlementView = Schemas['com.xavier.backend.settlement.model.dto.OrderSettlementView'];
export type PartitionView = Schemas['com.xavier.backend.settlement.model.dto.PartitionView'];
export type AttemptView = Schemas['com.xavier.backend.settlement.model.dto.AttemptView'];
export type DocumentView = Schemas['com.xavier.backend.fiscal.model.dto.DocumentView'];
export type CreatePartitionRequest = Schemas['com.xavier.backend.settlement.model.dto.CreatePartitionRequest'];
export type EqualPartsRequest = Schemas['com.xavier.backend.settlement.model.dto.EqualPartsRequest'];
export type CancelPartitionRequest = Schemas['com.xavier.backend.settlement.model.dto.CancelPartitionRequest'];
export type CreateAttemptRequest = Schemas['com.xavier.backend.settlement.model.dto.CreateAttemptRequest'];
export type ConfirmAttemptRequest = Schemas['com.xavier.backend.settlement.model.dto.ConfirmAttemptRequest'];
export type CancelAttemptRequest = Schemas['com.xavier.backend.settlement.model.dto.CancelAttemptRequest'];
export type ResolveAttemptRequest = Schemas['com.xavier.backend.settlement.model.dto.ResolveAttemptRequest'];
export type IssueDocumentRequest = Schemas['com.xavier.backend.settlement.model.dto.IssueDocumentRequest'];
export type CloseOrderRequest = Schemas['com.xavier.backend.settlement.model.dto.CloseOrderRequest'];

export const mzn = (amount: string) => ({ amount, currency: 'MZN' });

/**
 * What the server writes for a nullable field it leaves empty: the views are Java records and Jackson writes their
 * nulls (no NON_NULL inclusion). The generated types only know such a field as optional, so the fixtures say `null`
 * through this one cast, and every test reads the views as the server really sends them.
 */
export const NULL = null as unknown as undefined;

/** The permissions a cashier has in the terminal's store: split, collect and reprint (§3). */
export const CASHIER_PERMISSIONS = ['BILL_SPLIT', 'PAYMENT_COLLECT', 'FISCAL_DOCUMENT_REPRINT'].map(
  (permission) => `PERM_${permission}@${SESSION.storeId}`,
);

export const LINE_IDS = { steak: id(803), beer: id(804) };

/** M2's bill (order n.º 12): three Bitoques and two beers, nothing paid, nothing split. */
export const SETTLEMENT: SettlementView = {
  orderId: ORDER_ID,
  storeId: SESSION.storeId,
  lifecycle: 'OPEN',
  orderVersion: 4,
  due: mzn('1530.00'),
  paid: mzn('0.00'),
  balance: mzn('1530.00'),
  credit: mzn('0.00'),
  residualBalance: mzn('1530.00'),
  financialState: 'UNSETTLED',
  fiscalState: 'NONE',
  documentId: NULL,
  paymentStatus: 'UNPAID',
  openAttempts: [],
  partitions: [],
  coverage: [
    {
      lineId: LINE_IDS.steak,
      description: 'Bitoque',
      netQty: '3.000',
      gross: mzn('1350.00'),
      partitioned: '0.000',
      issued: '0.000',
      drafted: '0.000',
    },
    {
      lineId: LINE_IDS.beer,
      description: 'Cerveja 2M',
      netQty: '2.000',
      gross: mzn('180.00'),
      partitioned: '0.000',
      issued: '0.000',
      drafted: '0.000',
    },
  ],
  creditCharges: [],
  refunds: [],
  taxRoundingAdjustments: [],
  taxRoundingDifference: [],
  mode: 'DINE_IN',
};

export const PARTITION_IDS = { first: id(1001), second: id(1002), third: id(1003) };
const GROUP = id(1010);

/** One equal part of three, as the server cut it. */
export const part = (n: 1 | 2 | 3, fields: Partial<PartitionView> = {}): PartitionView => ({
  id: [PARTITION_IDS.first, PARTITION_IDS.second, PARTITION_IDS.third][n - 1],
  orderId: ORDER_ID,
  kind: 'AMOUNT',
  status: 'LIVE',
  groupId: GROUP,
  partNo: n,
  parts: 3,
  label: NULL,
  lines: [],
  due: mzn('510.00'),
  paid: mzn('0.00'),
  credit: mzn('0.00'),
  balance: mzn('510.00'),
  financialState: 'UNSETTLED',
  paymentStatus: 'UNPAID',
  invoiced: false,
  documentId: NULL,
  version: 0,
  createdAt: '2026-11-18T11:00:00Z',
  ...fields,
});

/** Two Bitoques for one diner. */
export const ITEMS_PARTITION: PartitionView = {
  id: id(1020),
  orderId: ORDER_ID,
  kind: 'ITEMS',
  status: 'LIVE',
  groupId: NULL,
  partNo: NULL,
  parts: NULL,
  label: NULL,
  documentId: NULL,
  lines: [
    { lineId: LINE_IDS.steak, description: 'Bitoque', quantity: '2.000', gross: mzn('900.00'), net: mzn('775.86'), tax: mzn('124.14') },
  ],
  due: mzn('900.00'),
  paid: mzn('0.00'),
  credit: mzn('0.00'),
  balance: mzn('900.00'),
  financialState: 'UNSETTLED',
  paymentStatus: 'UNPAID',
  invoiced: false,
  version: 0,
  createdAt: '2026-11-18T11:00:00Z',
};

/** The bill split in three equal parts. */
export const SPLIT_IN_THREE: SettlementView = {
  ...SETTLEMENT,
  orderVersion: 5,
  residualBalance: mzn('0.00'),
  partitions: [part(1), part(2), part(3)],
};

export const ATTEMPT_ID = id(1100);

export const attempt = (fields: Partial<AttemptView> = {}): AttemptView => ({
  id: ATTEMPT_ID,
  orderId: ORDER_ID,
  storeId: SESSION.storeId,
  methodCode: 'CASH',
  methodKind: 'CASH',
  status: 'SUCCEEDED',
  amount: mzn('1530.00'),
  tendered: mzn('2000.00'),
  change: mzn('470.00'),
  targetType: 'ORDER',
  partitionId: NULL,
  authorizationCode: NULL,
  createdAt: '2026-11-18T11:05:00Z',
  updatedAt: '2026-11-18T11:05:00Z',
  ...fields,
});

/** Everything paid in cash: settled, its document not issued yet. */
export const PAID: SettlementView = {
  ...SETTLEMENT,
  paid: mzn('1530.00'),
  balance: mzn('0.00'),
  residualBalance: mzn('0.00'),
  financialState: 'SETTLED',
  paymentStatus: 'PAID',
};

export const DOCUMENT_ID = id(1200);

export const ISSUED: SettlementView = { ...PAID, fiscalState: 'ISSUED', documentId: DOCUMENT_ID, orderVersion: 5 };

export const CLOSED: SettlementView = { ...ISSUED, lifecycle: 'CLOSED' };

export const DOCUMENT: DocumentView = {
  id: DOCUMENT_ID,
  orderId: ORDER_ID,
  status: 'ISSUED',
  documentType: 'SALE',
  seriesCode: 'A',
  number: 42,
  issuedAt: '2026-11-18T11:06:00Z',
  totalGross: mzn('1530.00'),
  nonFiscal: true,
  onAccount: false,
};

type Answer<Body = undefined> = (body: Body) => Response | Promise<Response>;

/**
 * The bill as the server has it now: every read answers the current value, so a read more or less (a reconnect, a
 * poll) never shifts what a test sees; `set` moves the server on, as a command it answers would.
 */
export function serverSettlement(initial: SettlementView = SETTLEMENT) {
  const state = { current: initial };
  const reads = recording<undefined>('get', `/api/v1/pos/orders/${ORDER_ID}/settlement`, () => HttpResponse.json(state.current));
  return {
    state,
    reads,
    set: (next: SettlementView) => {
      state.current = next;
    },
  };
}

/** `GET /api/v1/pos/orders/{id}/settlement`, answering from a queue whose last answer repeats. */
export const settlementEndpoint = (...answers: Answer[]) =>
  recording<undefined>('get', `/api/v1/pos/orders/${ORDER_ID}/settlement`, queue(...answers));

/** `POST /api/v1/pos/orders/{id}/partitions/equal`. */
export const equalPartsEndpoint = (...answers: Answer<EqualPartsRequest>[]) =>
  recording<EqualPartsRequest>('post', `/api/v1/pos/orders/${ORDER_ID}/partitions/equal`, queue(...answers));

/** `POST /api/v1/pos/orders/{id}/partitions`. */
export const partitionEndpoint = (...answers: Answer<CreatePartitionRequest>[]) =>
  recording<CreatePartitionRequest>('post', `/api/v1/pos/orders/${ORDER_ID}/partitions`, queue(...answers));

/** `POST /api/v1/pos/partitions/{id}/cancel`. */
export const cancelPartitionEndpoint = (partitionId: string, ...answers: Answer<CancelPartitionRequest>[]) =>
  recording<CancelPartitionRequest>('post', `/api/v1/pos/partitions/${partitionId}/cancel`, queue(...answers));

/** `POST /api/v1/pos/payment-attempts`. */
export const attemptEndpoint = (...answers: Answer<CreateAttemptRequest>[]) =>
  recording<CreateAttemptRequest>('post', '/api/v1/pos/payment-attempts', queue(...answers));

/** `GET /api/v1/pos/payment-attempts/{id}`. */
export const attemptReadEndpoint = (attemptId: string, ...answers: Answer[]) =>
  recording<undefined>('get', `/api/v1/pos/payment-attempts/${attemptId}`, queue(...answers));

/** `POST /api/v1/pos/payment-attempts/{id}/confirm`. */
export const confirmAttemptEndpoint = (attemptId: string, ...answers: Answer<ConfirmAttemptRequest>[]) =>
  recording<ConfirmAttemptRequest>('post', `/api/v1/pos/payment-attempts/${attemptId}/confirm`, queue(...answers));

/** `POST /api/v1/pos/payment-attempts/{id}/cancel`. */
export const cancelAttemptEndpoint = (attemptId: string, ...answers: Answer<CancelAttemptRequest>[]) =>
  recording<CancelAttemptRequest>('post', `/api/v1/pos/payment-attempts/${attemptId}/cancel`, queue(...answers));

/** `POST /api/v1/pos/payment-attempts/{id}/resolve`. */
export const resolveAttemptEndpoint = (attemptId: string, ...answers: Answer<ResolveAttemptRequest>[]) =>
  recording<ResolveAttemptRequest>('post', `/api/v1/pos/payment-attempts/${attemptId}/resolve`, queue(...answers));

/** `POST /api/v1/pos/orders/{id}/documents`. */
export const orderDocumentEndpoint = (...answers: Answer<IssueDocumentRequest>[]) =>
  recording<IssueDocumentRequest>('post', `/api/v1/pos/orders/${ORDER_ID}/documents`, queue(...answers));

/** `POST /api/v1/pos/partitions/{id}/documents`. */
export const partitionDocumentEndpoint = (partitionId: string, ...answers: Answer<IssueDocumentRequest>[]) =>
  recording<IssueDocumentRequest>('post', `/api/v1/pos/partitions/${partitionId}/documents`, queue(...answers));

/** `POST /api/v1/pos/documents/{id}/reprints`. */
export const reprintEndpoint = (documentId: string, ...answers: Answer[]) =>
  recording<undefined>('post', `/api/v1/pos/documents/${documentId}/reprints`, queue(...answers));

/** `POST /api/v1/pos/orders/{id}/close`. */
export const closeEndpoint = (...answers: Answer<CloseOrderRequest>[]) =>
  recording<CloseOrderRequest>('post', `/api/v1/pos/orders/${ORDER_ID}/close`, queue(...answers));
