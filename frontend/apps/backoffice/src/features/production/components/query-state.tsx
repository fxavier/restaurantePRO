import { DataState } from '@restaurantpro/ui';
import type { UseQueryResult } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { SectionError } from './section-error';

type QueryStateProps<T> = {
  query: UseQueryResult<T>;
  /** What the section lists, for the "no permission" message ("as impressoras"). */
  subject: string;
  isEmpty: (data: T) => boolean;
  emptyMessage: string;
  children: (data: T) => ReactNode;
};

/** One section's read: its own error (no permission, unavailable, failed), loading, empty, or the data. */
export function QueryState<T>({ query, subject, isEmpty, emptyMessage, children }: QueryStateProps<T>) {
  if (query.isError) {
    return <SectionError error={query.error} subject={subject} onRetry={() => void query.refetch()} />;
  }
  if (query.isPending) {
    return <DataState state="loading">{null}</DataState>;
  }
  return (
    <DataState state={isEmpty(query.data) ? 'empty' : 'ready'} emptyMessage={emptyMessage}>
      {children(query.data)}
    </DataState>
  );
}
