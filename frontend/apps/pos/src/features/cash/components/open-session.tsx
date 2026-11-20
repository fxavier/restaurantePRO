import { Button, formatMoney } from '@restaurantpro/ui';
import { CircleMinus, CirclePlus, Inbox, ReceiptText } from 'lucide-react';
import { useState } from 'react';
import { useEmployee, useStoreTimeZone } from '../../../lib/auth/use-employee';
import { useCommandsBlocked } from '../../../lib/live/use-connection';
import { MOVEMENTS, type MovementType } from '../cash';
import type { RegisterSummary, SessionDetails } from '../hooks/use-cash';
import { type MovementView, useDrawerKick } from '../hooks/use-cash-commands';
import { cashRefusal } from '../messages';
import { BlindCount } from './blind-count';
import { MovementDialog } from './movement-dialog';
import { SessionCard } from './session-card';

const ICONS = { CASH_IN: CirclePlus, CASH_OUT: CircleMinus, EXPENSE: ReceiptText } as const;

/**
 * The operator's open session with its commands (cash in, cash out, an approved expense, the drawer) and the blind
 * count that closes it.
 */
export function OpenSession({ session, register }: { session: SessionDetails; register: RegisterSummary | undefined }) {
  const employee = useEmployee();
  const timeZone = useStoreTimeZone();
  const blocked = useCommandsBlocked();
  const [moving, setMoving] = useState<MovementType>();
  const [outcome, setOutcome] = useState<string>();
  const drawer = useDrawerKick(() => setOutcome('Pedido de abertura da gaveta enviado.'));

  function recorded(movement: MovementView) {
    setOutcome(`${doneLabel(movement)}: ${movement.amount ? formatMoney(movement.amount) : ''}`);
    setMoving(undefined);
  }

  return (
    <>
      <SessionCard session={session} register={register} operator={employee?.name ?? ''} timeZone={timeZone}>
        {(Object.keys(MOVEMENTS) as MovementType[]).map((type) => {
          const Icon = ICONS[type];
          return (
            <Button key={type} variant="outline" size="touch" onClick={() => setMoving(type)}>
              <Icon aria-hidden="true" />
              {MOVEMENTS[type].action}
            </Button>
          );
        })}
        <Button
          variant="outline"
          size="touch"
          disabled={blocked || drawer.pending}
          aria-busy={drawer.pending}
          // After no answer, the next tap resends the same request (same key): the drawer opens once.
          onClick={() => (drawer.intent ? drawer.retry() : drawer.start(undefined, undefined))}
        >
          <Inbox aria-hidden="true" />
          Abrir gaveta
        </Button>
      </SessionCard>
      {/* Kept in place when empty, so nothing below moves when a command ends. */}
      <p role="status" aria-label="Último movimento" className="min-h-6 font-medium text-primary-hover">
        {outcome}
      </p>
      {drawer.error && (
        <p role="alert" className="font-medium text-error">
          {cashRefusal(drawer.error, 'a abertura da gaveta')}
        </p>
      )}
      {session.id && <BlindCount session={session} sessionId={session.id} />}
      {moving && session.id && (
        <MovementDialog
          type={moving}
          sessionId={session.id}
          currency={session.openingFloat?.currency}
          onClose={() => setMoving(undefined)}
          onRecorded={recorded}
        />
      )}
    </>
  );
}

function doneLabel(movement: MovementView): string {
  const type = movement.type as MovementType | undefined;
  return type && type in MOVEMENTS ? MOVEMENTS[type].done : 'Movimento registado';
}
