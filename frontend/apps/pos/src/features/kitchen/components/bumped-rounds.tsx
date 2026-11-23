import { useKitchenTickets } from '../hooks/use-kitchen-tickets';
import { loadFailure } from '../messages';
import { RoundList } from './kitchen-board';

type Props = { destination?: string; onChangedElsewhere: () => void };

/** The latest rounds taken off the screen (BUMPED), read only when asked for, so one bumped by mistake can come back. */
export function BumpedRounds({ destination, onChangedElsewhere }: Props) {
  const { data, isError, error } = useKitchenTickets({ destination, statuses: ['BUMPED'], latestBumped: true });
  return (
    <section aria-labelledby="kitchen-bumped" className="flex flex-col gap-3 rounded-xl border bg-card p-3">
      <h3 id="kitchen-bumped" className="text-xs font-semibold tracking-wide uppercase">
        Retirados
      </h3>
      {data ? (
        data.tickets.length === 0 ? (
          <p className="py-4 text-center text-sm text-muted-foreground">Nenhuma ronda retirada.</p>
        ) : (
          <RoundList tickets={data.tickets} onChangedElsewhere={onChangedElsewhere} className="grid grid-cols-1 gap-3 lg:grid-cols-3" />
        )
      ) : isError ? (
        <p role="alert">{loadFailure(error)}</p>
      ) : (
        <p role="status" className="py-4 text-center text-sm text-muted-foreground">
          A carregar as rondas retiradas…
        </p>
      )}
    </section>
  );
}
