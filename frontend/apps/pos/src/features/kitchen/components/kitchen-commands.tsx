import { Button } from '@restaurantpro/ui';
import { useEmployee } from '../../../lib/auth/use-employee';
import { useCommandsBlocked } from '../../../lib/live/use-connection';
import { useKitchenCommand } from '../hooks/use-kitchen-command';
import { isChangedElsewhere, type KitchenTicket, mayOperateKitchen, NEXT } from '../kitchen';
import { refusal } from '../messages';

type Props = { ticket: KitchenTicket; onChangedElsewhere: () => void };

/**
 * A card's commands, at the version the card shows: the state's main action full width, recall beside it. Hidden
 * without PRODUCTION_OPERATE, disabled without a connection (FC §11). An unknown outcome leaves only "Tentar novamente",
 * which resends the same key (FC §8).
 */
export function KitchenCommands({ ticket, onChangedElsewhere }: Props) {
  const employee = useEmployee();
  const blocked = useCommandsBlocked();
  const move = useKitchenCommand(ticket.id, { onChangedElsewhere });
  if (!mayOperateKitchen(employee)) return null;

  const awaitingRetry = !move.pending && move.intent !== undefined;
  const [main, ...others] = NEXT[ticket.status];
  return (
    <div className="flex flex-col gap-2">
      <Failure error={move.error} />
      {awaitingRetry ? (
        <Button size="touch-primary" className="w-full" disabled={blocked} onClick={move.retry}>
          Tentar novamente
        </Button>
      ) : (
        <>
          <Button
            size="touch-primary"
            className="w-full"
            disabled={blocked || move.pending}
            aria-busy={move.pending}
            onClick={() => move.start({ command: main.command, version: ticket.version }, undefined)}
          >
            {main.label}
          </Button>
          {others.map(({ command, label }) => (
            <Button
              key={command}
              variant="outline"
              size="touch"
              className="w-full"
              disabled={blocked || move.pending}
              onClick={() => move.start({ command, version: ticket.version }, undefined)}
            >
              {label}
            </Button>
          ))}
        </>
      )}
    </div>
  );
}

/** A refused command; one because the round moved is said by the board, as this card may have moved column. */
function Failure({ error }: { error: Error | undefined }) {
  if (!error || isChangedElsewhere(error)) return null;
  return (
    <p role="alert" className="rounded-lg bg-card px-2 py-1 text-sm font-medium text-error">
      {refusal(error)}
    </p>
  );
}
