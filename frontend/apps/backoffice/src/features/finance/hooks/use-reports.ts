import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import { dates, instants, type Period } from '../period';
import type {
  AuditEventView,
  ClosingStatus,
  ClosingView,
  DocumentSummary,
  InDoubtAttemptView,
  PaymentMethodView,
  ReceiptsReport,
  ReconciliationLine,
  RegisterSummary,
  SalesReport,
  SeriesView,
} from '../types';

export const FINANCE_KEY = ['backoffice', 'finance'] as const;
/** The cash reads a closing or day command changes: closings, the reconciliation (and the days it lists). */
export const CASH_KEY = [...FINANCE_KEY, 'cash'] as const;
export const SERIES_KEY = [...FINANCE_KEY, 'series'] as const;
export const METHODS_KEY = [...FINANCE_KEY, 'payment-methods'] as const;

export const PAGE_SIZE = 20;
export type Paged<Row> = { rows: Row[]; totalElements: number };

/** The sales report of the period per day (§5 reports): its rows and the server's totals. */
export function useSalesByDay(storeId: string, period: Period) {
  const api = useApi();
  return useQuery({
    queryKey: [...FINANCE_KEY, 'sales', storeId, period],
    queryFn: async (): Promise<SalesReport> => {
      const { data } = await api.GET('/api/v1/backoffice/reports/sales', {
        params: { query: { storeId, ...dates(period), groupBy: 'day' } },
      });
      return data as SalesReport;
    },
  });
}

/** What was received in the period per payment method, with the server's totals. */
export function useReceiptsByMethod(storeId: string, period: Period) {
  const api = useApi();
  return useQuery({
    queryKey: [...FINANCE_KEY, 'receipts', storeId, period],
    queryFn: async (): Promise<ReceiptsReport> => {
      const { data } = await api.GET('/api/v1/backoffice/reports/receipts', {
        params: { query: { storeId, ...dates(period), groupBy: 'method' } },
      });
      return data as ReceiptsReport;
    },
  });
}

/** The store's closings, newest first; `status` narrows them (SUBMITTED: those waiting for approval). */
export function useClosings(storeId: string, status: ClosingStatus | undefined, page: number) {
  const api = useApi();
  return useQuery({
    queryKey: [...CASH_KEY, 'closings', storeId, status, page],
    queryFn: async (): Promise<Paged<ClosingView>> => {
      const { data } = await api.GET('/api/v1/backoffice/cash/closings', {
        params: { query: { storeId, ...(status ? { status } : {}), page, size: PAGE_SIZE } },
      });
      return { rows: data?.content ?? [], totalElements: data?.totalElements ?? 0 };
    },
    placeholderData: keepPreviousData,
  });
}

/** The store's registers, for naming a closing's register; one page is a store's registers. */
export function useRegisters(storeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...FINANCE_KEY, 'registers', storeId],
    queryFn: async (): Promise<RegisterSummary[]> => {
      const { data } = await api.GET('/api/v1/backoffice/cash/registers', {
        params: { query: { storeId, page: 0, size: 100 } },
      });
      return data?.content ?? [];
    },
  });
}

/** Expected against counted per day, register and method in the period, with each day's state (§7, §8.3). */
export function useCashReconciliation(storeId: string, period: Period) {
  const api = useApi();
  return useQuery({
    queryKey: [...CASH_KEY, 'reconciliation', storeId, period],
    queryFn: async (): Promise<ReconciliationLine[]> => {
      const { data } = await api.GET('/api/v1/backoffice/cash/reconciliation', {
        params: { query: { storeId, ...dates(period) } },
      });
      return data ?? [];
    },
  });
}

/** Payment attempts whose outcome is not known yet: pending reconciliation, never failed (§7). */
export function useInDoubtAttempts(storeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...FINANCE_KEY, 'in-doubt', storeId],
    queryFn: async (): Promise<InDoubtAttemptView[]> => {
      const { data } = await api.GET('/api/v1/backoffice/reconciliation/in-doubt', { params: { query: { storeId } } });
      return data ?? [];
    },
  });
}

/** The store's document series; reading them needs FISCAL_MANAGE, a sensitive permission (step-up). */
export function useSeries(storeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...SERIES_KEY, storeId],
    queryFn: async (): Promise<SeriesView[]> => {
      const { data } = await api.GET('/api/v1/backoffice/fiscal/series', {
        params: { query: { storeId, page: 0, size: 100 } },
      });
      return data?.content ?? [];
    },
  });
}

/** The fiscal documents issued in the period, one page at a time. */
export function useDocuments(storeId: string, period: Period, page: number) {
  const api = useApi();
  return useQuery({
    queryKey: [...FINANCE_KEY, 'documents', storeId, period, page],
    queryFn: async (): Promise<Paged<DocumentSummary>> => {
      const { data } = await api.GET('/api/v1/backoffice/fiscal/documents', {
        params: { query: { storeId, ...instants(period), page, size: PAGE_SIZE } },
      });
      return { rows: data?.content ?? [], totalElements: data?.totalElements ?? 0 };
    },
    placeholderData: keepPreviousData,
  });
}

/** The company's payment methods; reading them needs PAYMENT_METHOD_MANAGE, a sensitive permission (step-up). */
export function usePaymentMethods() {
  const api = useApi();
  return useQuery({
    queryKey: METHODS_KEY,
    queryFn: async (): Promise<PaymentMethodView[]> => {
      const { data } = await api.GET('/api/v1/backoffice/payment-methods');
      return data ?? [];
    },
  });
}

/** The store's audit trail of the period, newest first, one page at a time. */
export function useAuditEvents(storeId: string, period: Period, page: number) {
  const api = useApi();
  return useQuery({
    queryKey: [...FINANCE_KEY, 'audit', storeId, period, page],
    queryFn: async (): Promise<Paged<AuditEventView>> => {
      const { data } = await api.GET('/api/v1/backoffice/audit/events', {
        params: { query: { storeId, ...instants(period), page, size: PAGE_SIZE } },
      });
      return { rows: data?.content ?? [], totalElements: data?.totalElements ?? 0 };
    },
    placeholderData: keepPreviousData,
  });
}
