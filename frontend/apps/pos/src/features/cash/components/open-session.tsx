import { Button, formatMoney } from '@restaurantpro/ui';
import { CircleMinus, CirclePlus, ReceiptText } from 'lucide-react';
import { useState } from 'react';
import { useEmployee, useStoreTimeZone } from '../../../lib/auth/use-employee';
import { MOVEMENTS, type MovementType } from '../cash';
import type { RegisterSummary, SessionDetails } from '../hooks/use-cash';
import type { MovementView } from '../hooks/use-cash-commands';
import { MovementDialog } from './movement-dialog';
import { SessionCard } from './session-card';

const ICONS = { CASH_IN: CirclePlus, CASH_OUT: CircleMinus, EXPENSE: ReceiptText } as const;

/** The operator's open session with its commands: cash in, cash out and an approved expense. */
export function OpenSession({ session, register }: { session: SessionDetails; register: RegisterSummary | undefined }) {
  const employee = useEmployee();
  const timeZone = useStoreTimeZone();
  const [moving, setMoving] = useState<MovementType>();
  const [last, setLast] = useState<MovementView>();

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
      </SessionCard>
      {/* Kept in place when empty, so nothing below moves when a movement is recorded. */}
      <p role="status" aria-label="Último movimento" className="min-h-6 font-medium text-primary-hover">
        {last && `${lastLabel(last)}: ${last.amount ? formatMoney(last.amount) : ''}`}
      </p>
      {moving && session.id && (
        <MovementDialog
          type={moving}
          sessionId={session.id}
          currency={session.openingFloat?.currency}
          onClose={() => setMoving(undefined)}
          onRecorded={(movement) => {
            setLast(movement);
            setMoving(undefined);
          }}
        />
      )}
    </>
  );
}

function lastLabel(movement: MovementView): string {
  const type = movement.type as MovementType | undefined;
  return type && type in MOVEMENTS ? MOVEMENTS[type].done : 'Movimento registado';
}
