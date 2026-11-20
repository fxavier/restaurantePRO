import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import { sendWaitingWhileInProgress } from '../../../lib/in-progress';
import { parseJobs, type PrintJob } from '../parse';
import type { PrintJobStatus } from '../types';
import { PRINT_JOBS_KEY } from './use-printer-commands';

export const JOB_PAGE_SIZE = 20;

export type JobPage = { jobs: PrintJob[]; totalElements: number };

/** One page of the store's print queue, newest first (the server's default order), filtered by state on the server. */
export function usePrintJobs(storeId: string, status: PrintJobStatus | undefined, page: number, size = JOB_PAGE_SIZE) {
  const api = useApi();
  return useQuery({
    queryKey: [...PRINT_JOBS_KEY, storeId, status ?? 'ALL', page, size],
    queryFn: async (): Promise<JobPage> => {
      const { data } = await api.GET('/api/v1/backoffice/print-jobs', {
        params: { query: { storeId, ...(status ? { status } : {}), page, size } },
      });
      return { jobs: parseJobs(data?.content ?? []), totalElements: data?.totalElements ?? 0 };
    },
    placeholderData: keepPreviousData,
  });
}

export type JobCommand = { jobId: string; idempotencyKey: string };

/**
 * Retry and acknowledge are one intent per tap, under one key for every resend (FC §8). Whatever the answer, the
 * queue is read again: a conflict means it changed.
 */
export function useRetryJob() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ jobId, idempotencyKey }: JobCommand) =>
      sendWaitingWhileInProgress(() =>
        api.POST('/api/v1/backoffice/print-jobs/{id}/retry', {
          params: { path: { id: jobId }, header: { 'Idempotency-Key': idempotencyKey } },
        }),
      ),
    onSettled: () => queryClient.invalidateQueries({ queryKey: PRINT_JOBS_KEY }),
  });
}

export function useAcknowledgeJob() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ jobId, idempotencyKey }: JobCommand) =>
      sendWaitingWhileInProgress(() =>
        api.POST('/api/v1/backoffice/print-jobs/{id}/acknowledge', {
          params: { path: { id: jobId }, header: { 'Idempotency-Key': idempotencyKey } },
        }),
      ),
    onSettled: () => queryClient.invalidateQueries({ queryKey: PRINT_JOBS_KEY }),
  });
}
