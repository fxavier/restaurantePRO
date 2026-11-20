import type { ApiClient } from '@restaurantpro/api-client';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { EmployeeCreateRequest, EmployeeSummary } from '../types';
import { PEOPLE_KEY } from './use-people-queries';

const BASE = '/api/v1/backoffice';

/**
 * A people command: never retried (FC §8), and every people list refetched once it has an answer, success or refusal,
 * so a conflict shows what changed. `secret`: its request carries a PIN, so the finished mutation leaves the cache at
 * once (`gcTime: 0`) and the PIN lives nowhere after the form closes.
 */
function usePeopleCommand<Variables, Result>(send: (api: ApiClient, variables: Variables) => Promise<Result>, secret = false) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (variables: Variables) => send(api, variables),
    onSettled: () => queryClient.invalidateQueries({ queryKey: PEOPLE_KEY }),
    ...(secret ? { gcTime: 0 } : {}),
  });
}

export function useHireEmployee() {
  return usePeopleCommand(
    async (api, body: EmployeeCreateRequest) => (await api.POST(`${BASE}/employees`, { body })).data as EmployeeSummary,
    true,
  );
}
