import type { UseMutationResult } from '@tanstack/react-query';

/**
 * A list row's command (submit, confirm, ship…): each tap is a new intent under a new Idempotency-Key; "Tentar
 * novamente" resends the last one as it was, same key (FC §8).
 */
export function useRowCommand<Variables extends { idempotencyKey: string }>(
  mutation: UseMutationResult<unknown, Error, Variables>,
) {
  return {
    run: (variables: Omit<Variables, 'idempotencyKey'>) =>
      mutation.mutate({ ...variables, idempotencyKey: crypto.randomUUID() } as Variables),
    retry: () => {
      if (mutation.variables) mutation.mutate(mutation.variables);
    },
    pending: mutation.isPending,
    error: mutation.error,
  };
}
