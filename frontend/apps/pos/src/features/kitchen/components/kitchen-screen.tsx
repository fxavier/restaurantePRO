import { Button, Skeleton } from '@restaurantpro/ui';
import { History, TriangleAlert } from 'lucide-react';
import { useState } from 'react';
import { useKitchenTickets } from '../hooks/use-kitchen-tickets';
import { useProductionDestinations } from '../hooks/use-production-destinations';
import { ACTIVE, stationsOf } from '../kitchen';
import { CHANGED_ELSEWHERE, loadFailure } from '../messages';
import { BumpedRounds } from './bumped-rounds';
import { KitchenBoard } from './kitchen-board';
import { StationFilter } from './station-filter';

/**
 * The display's content: the station filter and the history toggle, then the chosen station's active rounds (every
 * station's by default) and, when asked for, the latest taken off. A command refused because the round moved meanwhile
 * is said here, above both: its card may have moved column, or left the history.
 */
export function KitchenScreen() {
  const [station, setStation] = useState<string>();
  const [showBumped, setShowBumped] = useState(false);
  const [changedElsewhere, setChangedElsewhere] = useState(false);
  const onChangedElsewhere = () => setChangedElsewhere(true);
  return (
    <div className="flex flex-1 flex-col gap-4">
      {changedElsewhere && <ChangedElsewhere onClose={() => setChangedElsewhere(false)} />}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Stations chosen={station} onChoose={setStation} />
        <Button variant={showBumped ? 'default' : 'outline'} size="touch" aria-pressed={showBumped} onClick={() => setShowBumped(!showBumped)}>
          <History aria-hidden="true" />
          Retirados
        </Button>
      </div>
      <ActiveRounds destination={station} onChangedElsewhere={onChangedElsewhere} />
      {showBumped && <BumpedRounds destination={station} onChangedElsewhere={onChangedElsewhere} />}
    </div>
  );
}

/**
 * The station pills: the store's stations, then any other the active rounds name (the same query as the board's when no
 * station is chosen). "Todas" is the server's total; a station's count is what the page holds, exact unless the board
 * says rounds are left out. The pills wait for the store's stations, so they never reorder under a tap; if that read is
 * refused, the rounds' stations are offered.
 */
function Stations({ chosen, onChoose }: { chosen?: string; onChoose: (station: string | undefined) => void }) {
  const all = useKitchenTickets({ statuses: ACTIVE });
  const destinations = useProductionDestinations();
  if (!all.data || destinations.isPending) return <div />;
  return (
    <StationFilter
      stations={stationsOf(all.data.tickets, chosen, destinations.data)}
      total={all.data.total}
      chosen={chosen}
      onChoose={onChoose}
    />
  );
}

function ActiveRounds({ destination, onChangedElsewhere }: { destination?: string; onChangedElsewhere: () => void }) {
  const tickets = useKitchenTickets({ destination, statuses: ACTIVE });
  if (tickets.data) {
    const { tickets: shown, notShown } = tickets.data;
    if (shown.length === 0 && notShown === 0) return <p className="py-6 text-center text-muted-foreground">Nenhuma ronda em curso.</p>;
    return (
      <>
        {notShown > 0 && <NotShown count={notShown} />}
        <KitchenBoard tickets={shown} onChangedElsewhere={onChangedElsewhere} />
      </>
    );
  }
  if (tickets.isError) {
    return (
      <div className="flex flex-col items-center gap-3 py-6 text-center">
        <p role="alert">{loadFailure(tickets.error)}</p>
        <Button variant="outline" size="touch" onClick={() => void tickets.refetch()}>
          Tentar novamente
        </Button>
      </div>
    );
  }
  return (
    <div role="status" aria-label="A carregar as rondas…" className="grid grid-cols-1 gap-4 lg:grid-cols-3">
      {Array.from({ length: 3 }, (_, index) => (
        <Skeleton key={index} className="h-64 rounded-xl" />
      ))}
    </div>
  );
}

/** More active rounds than one page holds (spec §7: confirmed work is never hidden without a word). */
function NotShown({ count }: { count: number }) {
  return (
    <div role="status" aria-label="Rondas por mostrar" className="flex items-center gap-3 rounded-lg border border-warning bg-warning/15 px-4 py-2">
      <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
      <p className="flex-1 font-medium">
        Mais {count} {count === 1 ? 'ronda' : 'rondas'} em curso não {count === 1 ? 'cabe' : 'cabem'} no ecrã. Escolha uma estação ou retire as prontas.
      </p>
    </div>
  );
}

function ChangedElsewhere({ onClose }: { onClose: () => void }) {
  return (
    <div role="alert" className="flex items-center gap-3 rounded-lg border border-warning bg-warning/15 px-4 py-2">
      <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
      <p className="flex-1 font-medium">{CHANGED_ELSEWHERE}</p>
      <Button variant="outline" size="touch" onClick={onClose}>
        Fechar aviso
      </Button>
    </div>
  );
}
