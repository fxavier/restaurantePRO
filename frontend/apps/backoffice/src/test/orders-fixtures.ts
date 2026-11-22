import type { components } from '@restaurantpro/api-client';
import { http, HttpResponse } from 'msw';
import { api, baixa, signedIn } from './fixtures';
import { server } from './server';

type Schemas = components['schemas'];
export type OrderRow = Schemas['com.xavier.backend.orders.model.dto.OrderRow'];
export type OrderView = Schemas['com.xavier.backend.orders.model.dto.OrderView'];
export type LineView = Schemas['com.xavier.backend.orders.model.dto.LineView'];
export type BatchView = Schemas['com.xavier.backend.orders.model.dto.BatchView'];
export type AdjustmentView = Schemas['com.xavier.backend.orders.model.dto.LineView.AdjustmentView'];

export const mzn = (amount: string) => ({ amount, currency: 'MZN' });
const orderId = (orderNumber: number) => `50000000-0000-4000-8000-${String(orderNumber).padStart(12, '0')}`;
const EMPLOYEE = '30000000-0000-4000-8000-000000000001';

export function orderRow(orderNumber: number, extra: Partial<OrderRow> = {}): OrderRow {
  return {
    id: orderId(orderNumber),
    storeId: baixa.id,
    orderNumber,
    mode: 'DINE_IN',
    tableCode: 'M12',
    guests: 4,
    customerId: null,
    lifecycle: 'OPEN',
    businessDay: '2026-11-20',
    openedAt: '2026-11-20T18:05:00Z',
    closedAt: null,
    due: mzn('1250.50'),
    ...extra,
  };
}

const BATCH = '60000000-0000-4000-8000-000000000001';

export function line(id: string, description: string, extra: Partial<LineView> = {}): LineView {
  return {
    id,
    itemId: '70000000-0000-4000-8000-000000000001',
    itemCode: 'PRATO-1',
    description,
    variantId: null,
    variantName: null,
    kind: 'STANDARD',
    batchId: BATCH,
    status: 'CONFIRMED',
    quantity: '2',
    netQuantity: '2',
    unitPrice: mzn('450.00'),
    listUnitPrice: mzn('450.00'),
    gross: mzn('900.00'),
    discount: null,
    discountLabel: null,
    taxRatePercent: '16',
    notes: null,
    origin: null,
    preview: false,
    automatic: false,
    promotionCode: null,
    promotionName: null,
    components: [],
    adjustments: [],
    version: 0,
    ...extra,
  };
}

export const offer: AdjustmentView = {
  id: '80000000-0000-4000-8000-000000000001',
  seq: 1,
  kind: 'OFFER',
  quantity: '1',
  percent: null,
  previousUnitPrice: mzn('120.00'),
  unitPrice: mzn('0.00'),
  reason: 'Aniversário do cliente',
  rewardCode: null,
  operatorEmployeeId: EMPLOYEE,
  approverEmployeeId: null,
  at: '2026-11-20T18:40:00Z',
};

/** Order #1043: a confirmed round of two lines (one with an offer) and a draft line not yet sent. */
export function orderView(orderNumber = 1043, extra: Partial<OrderView> = {}): OrderView {
  const confirmed = [
    line('90000000-0000-4000-8000-000000000001', 'Frango à zambeziana'),
    line('90000000-0000-4000-8000-000000000002', 'Sumo de maracujá', {
      variantName: 'Grande',
      quantity: '1',
      unitPrice: mzn('0.00'),
      listUnitPrice: mzn('120.00'),
      gross: mzn('120.00'),
      discount: mzn('120.00'),
      discountLabel: 'Oferta',
      adjustments: [offer],
    }),
  ];
  return {
    id: orderId(orderNumber),
    storeId: baixa.id,
    orderNumber,
    mode: 'DINE_IN',
    tableId: 'a0000000-0000-4000-8000-000000000001',
    tableCode: 'M12',
    guests: 4,
    customerId: null,
    lifecycle: 'OPEN',
    billingLock: 'NONE',
    businessDay: '2026-11-20',
    openedAt: '2026-11-20T18:05:00Z',
    closedAt: null,
    openedByEmployeeId: EMPLOYEE,
    terminalId: 'b0000000-0000-4000-8000-000000000001',
    catalogVersion: 3,
    promotionVersion: null,
    draftGross: mzn('60.00'),
    due: mzn('900.00'),
    version: 4,
    batches: [
      {
        id: BATCH,
        orderId: orderId(orderNumber),
        batchNo: 1,
        status: 'DISPATCHED',
        confirmedAt: '2026-11-20T18:12:00Z',
        confirmedByEmployeeId: EMPLOYEE,
        orderVersion: 2,
        lines: confirmed,
      },
    ],
    lines: [
      ...confirmed,
      line('90000000-0000-4000-8000-000000000003', 'Pão com manteiga', {
        batchId: null,
        status: 'DRAFT',
        quantity: '1',
        unitPrice: mzn('60.00'),
        listUnitPrice: mzn('60.00'),
        gross: mzn('60.00'),
      }),
    ],
    ...extra,
  };
}

export function page<T>(content: T[], number = 0, size = 50, totalElements = content.length) {
  return { content, page: number, size, totalElements };
}

/** What the orders screen reads: the session, the stores, the store's orders and one order's detail. */
export function ordersServer({ rows = [] as OrderRow[], detail = orderView(), totalElements }: { rows?: OrderRow[]; detail?: OrderView; totalElements?: number } = {}) {
  const state = { queries: [] as URLSearchParams[], details: [] as string[] };
  server.use(...signedIn());
  server.use(
    http.get(api('/api/v1/backoffice/orders'), ({ request }) => {
      const query = new URL(request.url).searchParams;
      state.queries.push(query);
      return HttpResponse.json(page(rows, Number(query.get('page') ?? 0), 50, totalElements ?? rows.length));
    }),
    http.get(api('/api/v1/backoffice/orders/:id'), ({ params }) => {
      state.details.push(String(params.id));
      return HttpResponse.json(detail);
    }),
  );
  return state;
}
