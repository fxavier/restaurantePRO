import type { ReactNode } from 'react';
import { Button } from '../components/button';
import { Skeleton } from '../components/skeleton';

export type DataStateValue = 'loading' | 'empty' | 'error' | 'unavailable' | 'ready';

type DataStateProps = {
  state: DataStateValue;
  /** Refetches; offered on error and unavailable. */
  onRetry?: () => void;
  emptyMessage?: string;
  children: ReactNode;
};

/** The loading, empty, error and unavailable states every data view has (DS principle 5); the content when ready. */
export function DataState({ state, onRetry, emptyMessage = 'Sem resultados', children }: DataStateProps) {
  switch (state) {
    case 'loading':
      return (
        <div role="status" aria-busy="true" aria-label="A carregar…" className="flex flex-col gap-2">
          <Skeleton className="h-6 w-2/3" />
          <Skeleton className="h-6 w-1/2" />
          <Skeleton className="h-6 w-3/5" />
        </div>
      );
    case 'empty':
      return <p className="py-6 text-center text-muted-foreground">{emptyMessage}</p>;
    case 'error':
    case 'unavailable':
      return (
        <div className="flex flex-col items-center gap-3 py-6 text-center">
          <p role="alert">{state === 'error' ? 'Não foi possível carregar.' : 'Sem ligação ao serviço.'}</p>
          {onRetry && (
            <Button variant="outline" onClick={onRetry}>
              Tentar novamente
            </Button>
          )}
        </div>
      );
    case 'ready':
      return <>{children}</>;
  }
}
