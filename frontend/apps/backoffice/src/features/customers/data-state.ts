import type { DataStateValue } from '@restaurantpro/ui';
import { isUnavailable } from '../../lib/errors';

type QueryLike<T> = { isPending: boolean; isError: boolean; error: unknown; data: T | undefined };

/** A query as one of the DataState values (DS principle 5): no connection is "unavailable", any other failure "error". */
export function dataState<T>(query: QueryLike<T>, isEmpty: (data: T) => boolean = () => false): DataStateValue {
  if (query.isError) return isUnavailable(query.error) ? 'unavailable' : 'error';
  if (query.isPending || query.data === undefined) return 'loading';
  return isEmpty(query.data) ? 'empty' : 'ready';
}
