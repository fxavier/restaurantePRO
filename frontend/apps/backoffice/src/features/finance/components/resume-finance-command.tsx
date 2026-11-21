import { Button, Card, CardContent, CardDescription, CardHeader, CardTitle, ConfirmButton } from '@restaurantpro/ui';
import { useId, useState } from 'react';
import { clearPendingCommand, isStepUpRequired, readPendingCommand } from '../../../lib/step-up';
import { useTenantPin } from '../../../lib/tenant-pin';
import { commandLabel, fromPendingCommand } from '../finance-commands';
import { useFinanceCommand } from '../hooks/use-finance-commands';
import { CommandAlert } from './command-alert';

/**
 * Back from a step-up (FC §6): the finance command it interrupted, with "Continuar", which sends the same request
 * (under the same key where the contract takes one). The stored command is removed as soon as it is sent or
 * cancelled, so a reload never replays it; a command of another screen or store is left alone.
 */
export function ResumeFinanceCommand({ storeId }: { storeId: string }) {
  const tenantPin = useTenantPin();
  const titleId = useId();
  const [command, setCommand] = useState(() => {
    const pending = readPendingCommand(tenantPin.get()?.tenantId);
    return pending && fromPendingCommand(pending, storeId);
  });
  const send = useFinanceCommand();
  if (!command) return null;

  const resend = () => {
    clearPendingCommand();
    send.mutate(command, { onSuccess: () => setCommand(undefined) });
  };
  const cancel = () => {
    clearPendingCommand();
    setCommand(undefined);
  };
  const expired = isStepUpRequired(send.error);
  return (
    <Card role="region" aria-labelledby={titleId} className="border-primary">
      <CardHeader>
        <CardTitle id={titleId}>Operação por continuar</CardTitle>
        <CardDescription>Identidade confirmada. Reveja e continue.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <p>{commandLabel(command)}.</p>
        {command.type === 'rejectClosing' && <p className="text-sm text-muted-foreground">Motivo: {command.reason}</p>}
        {expired ? <Expired onClose={cancel} /> : <Continue error={send.error} pending={send.isPending} onCancel={cancel} onSend={resend} />}
      </CardContent>
    </Card>
  );
}

/** The step-up again: the command was cleared when sent, so only closing is left. */
function Expired({ onClose }: { onClose: () => void }) {
  return (
    <>
      <p role="alert" className="text-sm text-error">
        A confirmação de identidade expirou. Repita a operação.
      </p>
      <div className="flex justify-end">
        <Button type="button" variant="outline" onClick={onClose}>
          Fechar
        </Button>
      </div>
    </>
  );
}

type ContinueProps = { error: Error | null; pending: boolean; onCancel: () => void; onSend: () => void };

function Continue({ error, pending, onCancel, onSend }: ContinueProps) {
  return (
    <>
      {error && <CommandAlert error={error} pending={pending} onRetry={onSend} />}
      <div className="flex flex-wrap justify-end gap-2">
        <Button type="button" variant="outline" onClick={onCancel}>
          Cancelar
        </Button>
        <ConfirmButton pending={pending} onClick={onSend}>
          Continuar
        </ConfirmButton>
      </div>
    </>
  );
}
