import { ApiError } from '@restaurantpro/api-client';
import { Button, Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle, DataState } from '@restaurantpro/ui';
import type { UseQueryResult } from '@tanstack/react-query';
import { Lock, ShieldCheck } from 'lucide-react';
import type { ReactNode } from 'react';
import { useLocation } from 'react-router';
import { browser } from '../../../lib/browser';
import { isUnavailable } from '../../../lib/errors';
import { isStepUpRequired, stepUpUrl } from '../../../lib/step-up';
import { PAGE_SIZE } from '../hooks/use-people-queries';
import type { ListPage } from '../types';

type ListCardProps<Row> = {
  title: string;
  description: string;
  /** The tab's commands beside the title (create); shown only once the list has loaded. */
  actions?: ReactNode;
  query: UseQueryResult<ListPage<Row>>;
  /** What the list holds, for its refusals: "Sem permissão para ver {what}." */
  what: string;
  emptyMessage: string;
  onPage: (page: number) => void;
  children: (rows: Row[]) => ReactNode;
};

/**
 * One tab's list in Stitch's card (FC §9): its loading, empty, error and "Sem ligação" states, the refusals of the read
 * itself (a step-up, no permission), and the server's pages.
 */
export function ListCard<Row>({ title, description, actions, query, what, emptyMessage, onPage, children }: ListCardProps<Row>) {
  const data = query.data;
  // "Empty" only when the server holds none: a page past the end still shows the pager, to go back.
  const state = query.isPending ? 'loading' : data?.rows.length || data?.totalElements ? 'ready' : 'empty';
  return (
    <Card className="gap-4">
      <CardHeader>
        <CardTitle className="text-lg">{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
        {data && actions && <CardAction className="flex flex-wrap gap-2">{actions}</CardAction>}
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {query.isError ? (
          <ListError error={query.error} what={what} onRetry={() => void query.refetch()} />
        ) : (
          <DataState state={state} emptyMessage={emptyMessage}>
            {data && children(data.rows)}
            {data && <Pager page={data.page} totalElements={data.totalElements} onPage={onPage} />}
          </DataState>
        )}
      </CardContent>
    </Card>
  );
}

function Pager({ page, totalElements, onPage }: { page: number; totalElements: number; onPage: (page: number) => void }) {
  const pages = Math.ceil(totalElements / PAGE_SIZE);
  if (pages <= 1) return null;
  return (
    <nav aria-label="Páginas" className="flex items-center justify-end gap-2 text-sm">
      <Button variant="outline" size="sm" disabled={page === 0} onClick={() => onPage(page - 1)}>
        Anterior
      </Button>
      <span>
        Página {page + 1} de {pages}
      </span>
      <Button variant="outline" size="sm" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>
        Seguinte
      </Button>
    </nav>
  );
}

/** The read's own refusals: a fresh second factor to read it, no permission, no connection, anything else. */
function ListError({ error, what, onRetry }: { error: unknown; what: string; onRetry: () => void }) {
  const { pathname, search } = useLocation();
  if (isStepUpRequired(error)) {
    return (
      <Notice icon={ShieldCheck} text={`Para ver ${what}, confirme a sua identidade.`}>
        <Button onClick={() => browser.assign(stepUpUrl(error, `${pathname}${search}`))}>Confirmar identidade</Button>
      </Notice>
    );
  }
  if (error instanceof ApiError && error.status === 403) return <Notice icon={Lock} text={`Sem permissão para ver ${what}.`} />;
  return (
    <DataState state={isUnavailable(error) ? 'unavailable' : 'error'} onRetry={onRetry}>
      {null}
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
