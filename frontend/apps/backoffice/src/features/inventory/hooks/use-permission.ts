import { useSession } from '../../identity';
import { holds } from '../permissions';

/** Whether the signed-in user holds `PERM_<name>` for the store; a convenience for hiding controls (§10). */
export function usePermission(name: string, storeId: string): boolean {
  const { data: session } = useSession();
  return holds(session?.permissions ?? [], name, storeId);
}
