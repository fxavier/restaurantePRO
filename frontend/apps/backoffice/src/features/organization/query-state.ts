import type { DataStateValue } from '@restaurantpro/ui';
import { isUnavailable } from '../../lib/errors';

/**
 * What a read shows (FC §9, §11): a failure first ("Sem ligação" when no answer came), then loading until the data
 * arrives, then empty for a list with no items, else ready.
 */
export function queryState(error: unknown, data: unknown): DataStateValue {
  if (error) return isUnavailable(error) ? 'unavailable' : 'error';
  if (data === undefined) return 'loading';
  return Array.isArray(data) && data.length === 0 ? 'empty' : 'ready';
}
