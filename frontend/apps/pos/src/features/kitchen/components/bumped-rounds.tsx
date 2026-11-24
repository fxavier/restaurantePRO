import { History } from 'lucide-react';
import { useKitchenTickets } from '../hooks/use-kitchen-tickets';
import { loadFailure } from '../messages';
import { RoundList } from './kitchen-board';

type Props = { destination?: string; onChangedElsewhere: () => void };

/**
 * The latest rounds taken off the screen (BUMPED), read only when asked for, so one bumped by mistake can come back:
 * a white card under the board, its rounds neutral (they are history, not work).
 */
export function BumpedRounds({ destination, onChangedElsewhere }: Props) {
  const { data, isError, error } = useKitchenTickets({ destination, statuses: ['BUMPED'], latestBumped: true });
  return (
    <section aria-labelledby="kitchen-bumped" className="flex flex-col gap-3 rounded-2xl border bg-card p-4">
      <h3 id="kitchen-bumped" className="flex items-center gap-2 text-lg font-semibold">
        <History aria-hidden="true" className="size-5 text-muted-foreground" />
        Retirados
      </h3>
      {data ? (
        data.tickets.length === 0 ? (
          <p className="py-4 text-center text-muted-foreground">Nenhuma ronda retirada.</p>
        ) : (
          <RoundList tickets={data.tickets} onChangedElsewhere={onChangedElsewhere} className="grid grid-cols-1 gap-3 lg:grid-cols-3" />
        )
      ) : isError ? (
        <p role="alert">{loadFailure(error)}</p>
      ) : (
        <p role="status" className="py-4 text-center text-muted-foreground">
          A carregar as rondas retiradas…
        </p>
      )}
    </section>
  );
}
