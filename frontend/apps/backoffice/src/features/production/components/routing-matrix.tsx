import { Card, CardContent, CardDescription, CardHeader, CardTitle, DataState } from '@restaurantpro/ui';
import type { UseQueryResult } from '@tanstack/react-query';
import { Network } from 'lucide-react';
import type { ReactNode } from 'react';
import type { StoreTerminal } from '../hooks/use-store-terminals';
import type { Printer } from '../parse';
import type { DestinationRouteView, TerminalPrinterView } from '../types';
import { RoutesEditor } from './routes-editor';
import { SectionError } from './section-error';
import { TerminalPrintersEditor } from './terminal-printers-editor';

type RoutingMatrixProps = {
  storeId: string;
  printers: Printer[];
  routes: UseQueryResult<DestinationRouteView[]>;
  assignments: UseQueryResult<TerminalPrinterView[]>;
  terminals: UseQueryResult<StoreTerminal[]>;
};

/** Stitch's "Matriz rápida": which printer each destination and each terminal prints on. */
export function RoutingMatrix({ storeId, printers, routes, assignments, terminals }: RoutingMatrixProps) {
  return (
    <section aria-labelledby="routing-title" className="flex flex-col gap-4 rounded-2xl border bg-card p-4 lg:col-span-5">
      <div className="flex items-center gap-2">
        <Network aria-hidden="true" className="size-5 text-primary" />
        <h2 id="routing-title" className="text-xl font-semibold">
          Matriz de encaminhamento
        </h2>
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <Panel title="Rotas por destino" description="O destino de produção de cada artigo do catálogo vai para uma impressora.">
          <Loaded query={routes} subject="as rotas">
            {(data) => <RoutesEditor storeId={storeId} routes={data} printers={printers} />}
          </Loaded>
        </Panel>
        <Panel title="Impressora por terminal" description="Onde cada terminal imprime as consultas de mesa e os documentos.">
          <Loaded query={terminals} subject="os terminais" empty="Esta loja não tem terminais POS ativos.">
            {(terminalList) => (
              <Loaded query={assignments} subject="as impressoras dos terminais">
                {(data) => (
                  <TerminalPrintersEditor storeId={storeId} terminals={terminalList} assignments={data} printers={printers} />
                )}
              </Loaded>
            )}
          </Loaded>
        </Panel>
      </div>
    </section>
  );
}

function Panel({ title, description, children }: { title: string; description: string; children: ReactNode }) {
  return (
    <Card className="gap-4 bg-background">
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  );
}

type LoadedProps<T> = {
  query: UseQueryResult<T[]>;
  subject: string;
  /** Said when the list is empty; without it an empty list is shown as such. */
  empty?: string;
  children: (data: T[]) => ReactNode;
};

/** A read's loading, error and (optionally) empty states; its content once there. */
function Loaded<T>({ query, subject, empty, children }: LoadedProps<T>) {
  if (query.isError) return <SectionError error={query.error} subject={subject} onRetry={() => void query.refetch()} />;
  const state = query.isPending ? 'loading' : empty && query.data.length === 0 ? 'empty' : 'ready';
  return (
    <DataState state={state} emptyMessage={empty}>
      {query.data && children(query.data)}
    </DataState>
  );
}
