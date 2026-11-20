import type { ApiClient } from '@restaurantpro/api-client';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { AssignmentRequest, EmployeeCreateRequest, EmployeeSummary } from '../types';
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

/** The new PIN the manager sets; the POS asks the employee to change it at the next sign-in. */
export function useResetPin() {
  return usePeopleCommand(
    async (api, { employeeId, pin }: { employeeId: string; pin: string }) =>
      (await api.POST(`${BASE}/employees/{id}/reset-pin`, { params: { path: { id: employeeId } }, body: { pin } }))
        .data as EmployeeSummary,
    true,
  );
}

export function useAddAssignment(employeeId: string) {
  return usePeopleCommand(
    async (api, body: AssignmentRequest) =>
      (await api.POST(`${BASE}/employees/{id}/assignments`, { params: { path: { id: employeeId } }, body })).data,
  );
}

export function useRemoveAssignment(employeeId: string) {
  return usePeopleCommand(async (api, assignmentId: string) => {
    await api.DELETE(`${BASE}/employees/{id}/assignments/{assignmentId}`, {
      params: { path: { id: employeeId, assignmentId } },
    });
  });
}

export function useUnlockEmployee() {
  return usePeopleCommand(
    async (api, employee: EmployeeSummary) =>
      (await api.POST(`${BASE}/employees/{id}/unlock`, { params: { path: { id: employee.id ?? '' } } })).data as EmployeeSummary,
  );
}
