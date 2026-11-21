import type { ApiClient } from '@restaurantpro/api-client';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import { sendWaitingWhileInProgress } from '../../../lib/in-progress';
import { toCreateRequest, toUpdateRequest, type AccountValues } from '../account-requests';
import type {
  AssignmentRequest,
  EmployeeCreateRequest,
  EmployeeSummary,
  EmployeeUpdateRequest,
  RoleCreateRequest,
  RoleUpdateRequest,
  SupportGrantRequest,
} from '../types';
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

/**
 * Renames an employee or switches them off (`active: false`: no POS sign-in in any store); `version` is the one the form
 * was loaded from, so a newer one answers 409 VERSION_CONFLICT.
 */
export function useUpdateEmployee() {
  return usePeopleCommand(
    async (api, { employeeId, body }: { employeeId: string; body: EmployeeUpdateRequest }) =>
      (await api.PUT(`${BASE}/employees/{id}`, { params: { path: { id: employeeId } }, body })).data as EmployeeSummary,
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

/**
 * Creates a role, or with `roleId` replaces one; `version` is the one the form was loaded from, so a newer one answers
 * 409 VERSION_CONFLICT.
 */
export function useSaveRole() {
  return usePeopleCommand(async (api, { roleId, body }: { roleId?: string; body: RoleCreateRequest & { version: number } }) => {
    if (!roleId) return (await api.POST(`${BASE}/roles`, { body: { name: body.name, permissions: body.permissions } })).data;
    const update: RoleUpdateRequest = body;
    return (await api.PUT(`${BASE}/roles/{id}`, { params: { path: { id: roleId } }, body: update })).data;
  });
}

/** Opens an account, or with `userId` and the loaded `version` replaces one (409 VERSION_CONFLICT if newer). */
export function useSaveAccount() {
  return usePeopleCommand(
    async (api, { userId, values, version }: { userId?: string; values: AccountValues; version: number }) =>
      userId
        ? (await api.PUT(`${BASE}/users/{id}`, { params: { path: { id: userId } }, body: toUpdateRequest(values, version) })).data
        : (await api.POST(`${BASE}/users`, { body: toCreateRequest(values) })).data,
  );
}

/**
 * The contract requires an Idempotency-Key for both: the caller makes one per intent and sends it again for every retry
 * of that intent (FC §8); a 409 IDEMPOTENCY_IN_PROGRESS waits and resends it (FE-F25).
 */
export function useGrantSupport() {
  return usePeopleCommand(
    async (api, { body, idempotencyKey }: { body: SupportGrantRequest; idempotencyKey: string }) =>
      (
        await sendWaitingWhileInProgress(() =>
          api.POST(`${BASE}/support-grants`, { body, params: { header: { 'Idempotency-Key': idempotencyKey } } }),
        )
      ).data,
  );
}

export function useRevokeSupport() {
  return usePeopleCommand(
    async (api, { grantId, idempotencyKey }: { grantId: string; idempotencyKey: string }) =>
      (
        await api.POST(`${BASE}/support-grants/{id}/revoke`, {
          params: { path: { id: grantId }, header: { 'Idempotency-Key': idempotencyKey } },
        })
      ).data,
  );
}

export function useUnlockEmployee() {
  return usePeopleCommand(
    async (api, employee: EmployeeSummary) =>
      (await api.POST(`${BASE}/employees/{id}/unlock`, { params: { path: { id: employee.id ?? '' } } })).data as EmployeeSummary,
  );
}
