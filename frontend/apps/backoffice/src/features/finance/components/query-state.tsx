import { ApiError } from '@restaurantpro/api-client';
import { Button, DataState } from '@restaurantpro/ui';
import { Lock, ShieldCheck } from 'lucide-react';
import type { ReactNode } from 'react';
import { useLocation } from 'react-router';
import { browser } from '../../../lib/browser';
import { isUnavailable } from '../../../lib/errors';
import { isStepUpRequired, stepUpUrl } from '../../../lib/step-up';

type Query = { isPending: boolean; isError: boolean; error: unknown; refetch: () => unknown };

type QueryStateProps = {
  query: Query;
  /** Whether the answer has nothing to show. */
  empty: boolean;
  emptyMessage: string;
  /** What the user cannot see, for the 403 and step-up texts: "os relatórios desta loja". */
  subject: string;
  children: ReactNode;
};

/**
 * One read's states (DS principle 5): loading, empty, the content, and its failures. A sensitive read asks for a
 * step-up and comes back to this tab; a 403 says so (the server decides); no connection offers a retry.
 */
export function QueryState({ query, empty, emptyMessage, subject, children }: QueryStateProps) {
  const { pathname, search } = useLocation();
  if (query.isError) {
    const error = query.error;
    if (isStepUpRequired(error)) {
      return (
        <Notice icon={ShieldCheck} text={`Para ver ${subject}, confirme a sua identidade.`}>
          <Button onClick={() => browser.assign(stepUpUrl(error, `${pathname}${search}`))}>Confirmar identidade</Button>
        </Notice>
      );
    }
    if (error instanceof ApiError && error.status === 403) {
      return <Notice icon={Lock} text={`Sem permissão para ver ${subject}.`} />;
    }
    return (
      <DataState state={isUnavailable(error) ? 'unavailable' : 'error'} onRetry={() => void query.refetch()}>
        {null}
      </DataState>
    );
  }
  return (
    <DataState state={query.isPending ? 'loading' : empty ? 'empty' : 'ready'} emptyMessage={emptyMessage}>
      {children}
    </DataState>
  );
}

function Notice({ icon: Icon, text, children }: { icon: typeof Lock; text: string; children?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-3 py-6 text-center">
      <Icon aria-hidden="true" className="size-8 text-muted-foreground" />
      <p>{text}</p>
      {children}
    </div>
  );
}
