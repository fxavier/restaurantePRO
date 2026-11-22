import { useKitchenTickets } from '../hooks/use-kitchen-tickets';
import { loadFailure } from '../messages';
import { RoundList } from './kitchen-board';

type Props = { destination?: string; onChangedElsewhere: () => void };

/** The latest rounds taken off the screen (BUMPED), read only when asked for, so one bumped by mistake can come back. */
export function BumpedRounds({ destination, onChangedElsewhere }: Props) {
  const bumped = useKitchenTickets({ destination, statuses: ['BUMPED'], latestFirst: true });
  return (
    <section aria-labelledby="kitchen-bumped" className="flex flex-col gap-3 rounded-xl border bg-card p-3">
      <h3 id="kitchen-bumped" className="text-xs font-semibold tracking-wide uppercase">
        Retirados
      </h3>
      {bumped.data ? (
        bumped.data.length === 0 ? (
          <p className="py-4 text-center text-sm text-muted-foreground">Nenhuma ronda retirada.</p>
        ) : (
          <RoundList tickets={bumped.data} onChangedElsewhere={onChangedElsewhere} className="grid grid-cols-1 gap-3 lg:grid-cols-3" />
        )
      ) : bumped.isError ? (
        <p role="alert">{loadFailure(bumped.error)}</p>
      ) : (
        <p role="status" className="py-4 text-center text-sm text-muted-foreground">
          A carregar as rondas retiradas…
        </p>
      )}
    </section>
  );
}
