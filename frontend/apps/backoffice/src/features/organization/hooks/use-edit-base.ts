import { useState } from 'react';
import { isVersionConflict } from '../server-errors';

/**
 * What an edit form was loaded from, and its version is what the edit sends. A 409 VERSION_CONFLICT replaces it with
 * the server's current state (`read`), so the form shows what the other person saved and the next save is made at
 * that version (FC §9: refetch, show what changed, let the user retry). Any other error goes back to the form.
 */
export function useEditBase<T>(initial: T) {
  const [base, setBase] = useState(initial);
  const [reloaded, setReloaded] = useState(false);
  const save = async (send: (base: T) => Promise<unknown>, read: () => Promise<T>) => {
    try {
      await send(base);
    } catch (error) {
      if (!isVersionConflict(error)) throw error;
      setBase(await read());
      setReloaded(true);
    }
  };
  return { base, reloaded, save };
}
