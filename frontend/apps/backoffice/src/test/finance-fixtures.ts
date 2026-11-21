import type { components } from '@restaurantpro/api-client';
import { http, HttpResponse } from 'msw';
import { admin, api, baixa, signedIn, type SessionView } from './fixtures';
import { server } from './server';

type Schemas = components['schemas'];
type Money = Schemas['com.xavier.backend.shared.Money'];
export type ClosingView = Schemas['com.xavier.backend.cash.model.dto.ClosingView'];
export type ReconciliationLine = Schemas['com.xavier.backend.cash.model.dto.ReconciliationLine'];
export type SalesRow = Schemas['com.xavier.backend.reporting.model.dto.SalesRow'];
export type ReceiptsRow = Schemas['com.xavier.backend.reporting.model.dto.ReceiptsRow'];
export type SeriesView = Schemas['com.xavier.backend.fiscal.model.dto.SeriesView'];
export type DocumentSummary = Schemas['com.xavier.backend.fiscal.model.dto.DocumentSummary'];
export type PaymentMethodView = Schemas['com.xavier.backend.settlement.model.dto.PaymentMethodView'];
export type AuditEventView = Schemas['com.xavier.backend.audit.model.dto.AuditEventView'];
export type InDoubtAttemptView = Schemas['com.xavier.backend.settlement.reconciliation.model.dto.InDoubtAttemptView'];
export type RegisterSummary = Schemas['com.xavier.backend.cash.model.dto.RegisterSummary'];

/** Every permission the finance tabs need (backend `access.Permission`), company-wide. */
export const FINANCE_PERMISSIONS = [
  'PERM_CASH_VIEW',
  'PERM_CASH_CLOSING_APPROVE',
  'PERM_CASH_DAY_CLOSE',
  'PERM_CASH_DAY_REOPEN',
  'PERM_REPORT_VIEW',
  'PERM_FISCAL_DOCUMENT_VIEW',
  'PERM_FISCAL_MANAGE',
  'PERM_PAYMENT_METHOD_MANAGE',
  'PERM_AUDIT_VIEW',
];

export const financeAdmin: SessionView = { ...admin, permissions: [...admin.permissions, ...FINANCE_PERMISSIONS] };

export const mzn = (amount: string): Money => ({ amount, currency: 'MZN' });

export const DAY_1 = 'dddd0000-0000-4000-8000-000000000001';
export const DAY_2 = 'dddd0000-0000-4000-8000-000000000002';
export const REGISTER_1 = 'eeee0000-0000-4000-8000-000000000001';

export const caixaPrincipal: RegisterSummary = {
  id: REGISTER_1,
  storeId: baixa.id,
  code: 'CX1',
  name: 'Caixa principal',
  active: true,
  version: 0,
};

export function closing(id: string, status = 'SUBMITTED', extra: Partial<ClosingView> = {}): ClosingView {
  return {
    id,
    storeId: baixa.id,
    cashDayId: DAY_1,
    registerId: REGISTER_1,
    dayRound: 1,
    attemptNo: 1,
    status,
    version: 1,
    lines: [
      { methodCode: 'CASH', expected: mzn('590.50'), counted: mzn('590.50'), difference: mzn('0.00') },
      { methodCode: 'CARD', expected: mzn('1240.80'), counted: mzn('1200.00'), difference: mzn('-40.80'), justification: 'Talão em falta' },
    ],
    ...extra,
  };
}

export function reconciliationLine(extra: Partial<ReconciliationLine> = {}): ReconciliationLine {
  return {
    cashDayId: DAY_1,
    businessDate: '2026-11-19',
    dayStatus: 'OPERATIONALLY_CLOSED',
    dayRound: 1,
    registerId: REGISTER_1,
    closingId: 'cccc0000-0000-4000-8000-000000000001',
    closingStatus: 'APPROVED',
    methodCode: 'CASH',
    expected: mzn('590.50'),
    counted: mzn('590.50'),
    adjustments: mzn('0.00'),
    difference: mzn('0.00'),
    ...extra,
  };
}

export function salesRow(key: string, label: string, gross: string, documents = 1): SalesRow {
  return { key, label, gross: mzn(gross), net: mzn(gross), tax: mzn('0.00'), quantity: '1', documents };
}

export function receiptsRow(key: string, label: string, received: string): ReceiptsRow {
  return { key, label, received: mzn(received), onAccount: mzn('0.00'), debtCollected: mzn('0.00') };
}

export function reportView<Row>(rows: Row[], totals: Row) {
  return {
    storeId: baixa.id,
    from: '2026-11-14',
    to: '2026-11-20',
    groupBy: 'day',
    final: false,
    pendingFacts: 0,
    asOf: '2026-11-20T10:00:00Z',
    rows,
    totals,
  };
}

export function page<Row>(content: Row[], pageNumber = 0, size = 50, totalElements = content.length) {
  return { content, page: pageNumber, size, totalElements };
}

/** What the finance screen reads, each answer replaceable by a test; every request's URL is recorded. */
export type FinanceData = {
  sales: SalesRow[];
  salesTotals: SalesRow;
  receipts: ReceiptsRow[];
  receiptsTotals: ReceiptsRow;
  closings: ClosingView[];
  reconciliation: ReconciliationLine[];
  inDoubt: InDoubtAttemptView[];
  series: SeriesView[];
  documents: DocumentSummary[];
  methods: PaymentMethodView[];
  events: AuditEventView[];
};

export function emptyFinance(): FinanceData {
  return {
    sales: [],
    salesTotals: salesRow('total', 'Total', '0.00', 0),
    receipts: [],
    receiptsTotals: receiptsRow('total', 'Total', '0.00'),
    closings: [],
    reconciliation: [],
    inDoubt: [],
    series: [],
    documents: [],
    methods: [],
    events: [],
  };
}

/** A signed-in finance administrator; the handlers answer from `data`, which a test may change between requests. */
export function financeServer(overrides: Partial<FinanceData> = {}, session: SessionView = financeAdmin) {
  const data: FinanceData = { ...emptyFinance(), ...overrides };
  const requests: string[] = [];
  const record = (request: Request) => {
    const url = new URL(request.url);
    requests.push(url.pathname + url.search);
  };
  const base = '/api/v1/backoffice';
  server.use(...signedIn({ session }));
  server.use(
    http.get(api(`${base}/reports/sales`), ({ request }) => {
      record(request);
      return HttpResponse.json(reportView(data.sales, data.salesTotals));
    }),
    http.get(api(`${base}/reports/receipts`), ({ request }) => {
      record(request);
      return HttpResponse.json(reportView(data.receipts, data.receiptsTotals));
    }),
    http.get(api(`${base}/cash/closings`), ({ request }) => {
      record(request);
      return HttpResponse.json(page(data.closings, 0, 20));
    }),
    http.get(api(`${base}/cash/registers`), ({ request }) => {
      record(request);
      return HttpResponse.json(page([caixaPrincipal], 0, 100));
    }),
    http.get(api(`${base}/cash/reconciliation`), ({ request }) => {
      record(request);
      return HttpResponse.json(data.reconciliation);
    }),
    http.get(api(`${base}/reconciliation/in-doubt`), ({ request }) => {
      record(request);
      return HttpResponse.json(data.inDoubt);
    }),
    http.get(api(`${base}/fiscal/series`), ({ request }) => {
      record(request);
      return HttpResponse.json(page(data.series));
    }),
    http.get(api(`${base}/fiscal/documents`), ({ request }) => {
      record(request);
      return HttpResponse.json(page(data.documents, 0, 20));
    }),
    http.get(api(`${base}/payment-methods`), ({ request }) => {
      record(request);
      return HttpResponse.json(data.methods);
    }),
    http.get(api(`${base}/audit/events`), ({ request }) => {
      record(request);
      return HttpResponse.json(page(data.events));
    }),
  );
  return { data, requests };
}
