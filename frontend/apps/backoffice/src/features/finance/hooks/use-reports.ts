import type { ApiClient } from '@restaurantpro/api-client';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import { dates, instants, type Period } from '../period';
import type {
  AuditEventView,
  CashSessionStatus,
  ClosingStatus,
  ClosingView,
  DocumentSummary,
  EmployeeSummary,
  InDoubtAttemptView,
  PaymentMethodView,
  ReceiptsReport,
  ReconciliationLine,
  RegisterSummary,
  SalesReport,
  SeriesView,
  SessionRow,
} from '../types';

export const FINANCE_KEY = ['backoffice', 'finance'] as const;
/** The cash reads a closing or day command changes: closings, the reconciliation (and the days it lists). */
export const CASH_KEY = [...FINANCE_KEY, 'cash'] as const;
export const SERIES_KEY = [...FINANCE_KEY, 'series'] as const;
export const METHODS_KEY = [...FINANCE_KEY, 'payment-methods'] as const;

export const PAGE_SIZE = 20;
export type Paged<Row> = { rows: Row[]; totalElements: number };

/** One finance read under `key`; a paged one keeps the previous page on screen while the next loads. */
function useFinanceQuery<Result>(key: readonly unknown[], read: (api: ApiClient) => Promise<Result>, paged = false) {
  const api = useApi();
  return useQuery({ queryKey: key, queryFn: () => read(api), ...(paged ? { placeholderData: keepPreviousData } : {}) });
}

/** A page of the server's answer, as the tables page it (§9: server-side paging). */
function paged<Row>(data: { content: Row[]; totalElements: number } | undefined): Paged<Row> {
  return { rows: data?.content ?? [], totalElements: data?.totalElements ?? 0 };
}

/** A report of the period (§5 reports), grouped as asked: its rows and the server's totals. */
function useReport<Report>(report: 'sales' | 'receipts', groupBy: string, storeId: string, period: Period) {
  return useFinanceQuery([...FINANCE_KEY, report, storeId, period], (api) =>
    api
      .GET(`/api/v1/backoffice/reports/${report}`, { params: { query: { storeId, ...dates(period), groupBy } } })
      .then(({ data }) => data as Report),
  );
}

/** The sales of the period per day. */
export function useSalesByDay(storeId: string, period: Period) {
  return useReport<SalesReport>('sales', 'day', storeId, period);
}

/** What was received in the period per payment method. */
export function useReceiptsByMethod(storeId: string, period: Period) {
  return useReport<ReceiptsReport>('receipts', 'method', storeId, period);
}

/** The store's closings, oldest first; `status` narrows them (SUBMITTED: those waiting for approval). */
export function useClosings(storeId: string, status: ClosingStatus | undefined, page: number) {
  const query = { storeId, ...(status ? { status } : {}), page, size: PAGE_SIZE };
  return useFinanceQuery(
    [...CASH_KEY, 'closings', storeId, status, page],
    (api): Promise<Paged<ClosingView>> =>
      api.GET('/api/v1/backoffice/cash/closings', { params: { query } }).then(({ data }) => paged(data)),
    true,
  );
}

/** The store's cash sessions opened in the period's business dates, newest first; `status` narrows them. */
export function useCashSessions(storeId: string, period: Period, status: CashSessionStatus | undefined, page: number) {
  const query = { storeId, ...dates(period), ...(status ? { status } : {}), page, size: PAGE_SIZE };
  return useFinanceQuery(
    [...CASH_KEY, 'sessions', storeId, period, status, page],
    (api): Promise<Paged<SessionRow>> =>
      api.GET('/api/v1/backoffice/cash/sessions', { params: { query } }).then(({ data }) => paged(data)),
    true,
  );
}

/**
 * The store's employees, to name a session's operator. The list needs EMPLOYEE_MANAGE: without it (`enabled` false)
 * nothing is asked and the sessions still show, each operator by its id.
 */
export function useEmployees(storeId: string, enabled: boolean) {
  const api = useApi();
  return useQuery({
    queryKey: [...FINANCE_KEY, 'employees', storeId],
    queryFn: (): Promise<EmployeeSummary[]> =>
      api
        .GET('/api/v1/backoffice/employees', { params: { query: { storeId, page: 0, size: 200 } } })
        .then(({ data }) => data?.content ?? []),
    enabled,
  });
}

/** A store's short list in one page (a store has a handful of registers and series): no paging UI. */
function useStoreList<Row>(key: readonly unknown[], path: 'cash/registers' | 'fiscal/series', storeId: string) {
  return useFinanceQuery(key, (api) =>
    api
      .GET(`/api/v1/backoffice/${path}`, { params: { query: { storeId, page: 0, size: 100 } } })
      .then(({ data }) => (data?.content ?? []) as Row[]),
  );
}

/** The store's registers, for naming a closing's register. */
export function useRegisters(storeId: string) {
  return useStoreList<RegisterSummary>([...FINANCE_KEY, 'registers', storeId], 'cash/registers', storeId);
}

/** Expected against counted per day, register and method in the period, with each day's state (§7, §8.3). */
export function useCashReconciliation(storeId: string, period: Period) {
  return useFinanceQuery(
    [...CASH_KEY, 'reconciliation', storeId, period],
    (api): Promise<ReconciliationLine[]> =>
      api
        .GET('/api/v1/backoffice/cash/reconciliation', { params: { query: { storeId, ...dates(period) } } })
        .then(({ data }) => data ?? []),
  );
}

/** Payment attempts whose outcome is not known yet: pending reconciliation, never failed (§7). */
export function useInDoubtAttempts(storeId: string) {
  return useFinanceQuery(
    [...FINANCE_KEY, 'in-doubt', storeId],
    (api): Promise<InDoubtAttemptView[]> =>
      api.GET('/api/v1/backoffice/reconciliation/in-doubt', { params: { query: { storeId } } }).then(({ data }) => data ?? []),
  );
}

/** The store's document series; reading them needs FISCAL_MANAGE, a sensitive permission (step-up). */
export function useSeries(storeId: string) {
  return useStoreList<SeriesView>([...SERIES_KEY, storeId], 'fiscal/series', storeId);
}

/** A page of what happened in the period, newest first, filtered by the period's instants. */
function usePeriodPage<Row>(path: 'fiscal/documents' | 'audit/events', storeId: string, period: Period, page: number) {
  const query = { storeId, ...instants(period), page, size: PAGE_SIZE };
  return useFinanceQuery(
    [...FINANCE_KEY, path, storeId, period, page],
    (api) => api.GET(`/api/v1/backoffice/${path}`, { params: { query } }).then(({ data }) => paged(data) as Paged<Row>),
    true,
  );
}

/** The fiscal documents issued in the period. */
export function useDocuments(storeId: string, period: Period, page: number) {
  return usePeriodPage<DocumentSummary>('fiscal/documents', storeId, period, page);
}

/** The company's payment methods; reading them needs PAYMENT_METHOD_MANAGE, a sensitive permission (step-up). */
export function usePaymentMethods() {
  return useFinanceQuery(METHODS_KEY, (api): Promise<PaymentMethodView[]> =>
    api.GET('/api/v1/backoffice/payment-methods').then(({ data }) => data ?? []),
  );
}

/** The store's audit trail of the period. */
export function useAuditEvents(storeId: string, period: Period, page: number) {
  return usePeriodPage<AuditEventView>('audit/events', storeId, period, page);
}
