import { useState } from 'react';

/**
 * A command whose arguments are secrets (a PIN): pending and error state only, so the arguments are never kept,
 * unlike a TanStack mutation's variables in the mutation cache. It never retries (FC §8).
 */
export function useSecretCommand<Args extends unknown[]>(run: (...args: Args) => Promise<void>) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<Error>();

  /** @returns the failure, or nothing when the command succeeded */
  async function execute(...args: Args): Promise<Error | undefined> {
    setPending(true);
    setError(undefined);
    try {
      await run(...args);
      return undefined;
    } catch (thrown) {
      const failure = thrown instanceof Error ? thrown : new Error('Unexpected failure');
      setError(failure);
      return failure;
    } finally {
      setPending(false);
    }
  }

  return { execute, pending, error, clearError: () => setError(undefined) };
}
