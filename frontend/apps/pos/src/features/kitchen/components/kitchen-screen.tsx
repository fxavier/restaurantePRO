import { Button, Skeleton } from '@restaurantpro/ui';
import { History, TriangleAlert } from 'lucide-react';
import { useState } from 'react';
import { useKitchenTickets } from '../hooks/use-kitchen-tickets';
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

/** The station pills, from every station's active rounds (the same query as the board's when no station is chosen). */
function Stations({ chosen, onChoose }: { chosen?: string; onChoose: (station: string | undefined) => void }) {
  const all = useKitchenTickets({ statuses: ACTIVE });
  if (!all.data) return <div />;
  return <StationFilter stations={stationsOf(all.data, chosen)} total={all.data.length} chosen={chosen} onChoose={onChoose} />;
}

function ActiveRounds({ destination, onChangedElsewhere }: { destination?: string; onChangedElsewhere: () => void }) {
  const tickets = useKitchenTickets({ destination, statuses: ACTIVE });
  if (tickets.data) {
    if (tickets.data.length === 0) return <p className="py-6 text-center text-muted-foreground">Nenhuma ronda em curso.</p>;
    return <KitchenBoard tickets={tickets.data} onChangedElsewhere={onChangedElsewhere} />;
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
