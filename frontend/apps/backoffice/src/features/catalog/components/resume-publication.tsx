import { Button, Card, CardContent, CardDescription, CardHeader, CardTitle, ConfirmButton } from '@restaurantpro/ui';
import { useId, useState } from 'react';
import { clearPendingCommand, isStepUpRequired, readPendingCommand } from '../../../lib/step-up';
import { useTenantPin } from '../../../lib/tenant-pin';
import { usePublishRelease } from '../hooks/use-catalog-commands';
import { fromPendingPublication } from '../release-commands';
import { CommandAlert } from './form-parts';

/**
 * Back from a step-up (FC §6): the publication it interrupted, with "Continuar", which sends the same request under
 * the same key. The stored command is removed as soon as it is sent, so a reload never replays it; a command that is
 * not a publication of this store is left for its own screen.
 */
export function ResumePublication({ storeId }: { storeId: string }) {
  const tenantPin = useTenantPin();
  const titleId = useId();
  const [publication, setPublication] = useState(() => {
    const pending = readPendingCommand(tenantPin.get()?.tenantId);
    return pending && fromPendingPublication(pending, storeId);
  });
  const publish = usePublishRelease(storeId);
  if (!publication) return null;

  const send = () => {
    clearPendingCommand();
    publish.mutate(publication, { onSuccess: () => setPublication(undefined) });
  };
  const cancel = () => {
    clearPendingCommand();
    setPublication(undefined);
  };
  return (
    <Card role="region" aria-labelledby={titleId} className="border-primary">
      <CardHeader>
        <CardTitle id={titleId}>Publicação por continuar</CardTitle>
        <CardDescription>Identidade confirmada. Continue a publicação da versão que tinha confirmado.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {publication.note && <p className="text-sm">Nota: {publication.note}</p>}
        {publish.error && !isStepUpRequired(publish.error) && <CommandAlert error={publish.error} pending={publish.isPending} onRetry={send} />}
        {isStepUpRequired(publish.error) && <p role="alert" className="text-sm text-error">A confirmação de identidade expirou. Publique de novo.</p>}
        <div className="flex flex-wrap justify-end gap-2">
          <Button type="button" variant="outline" onClick={cancel}>
            Cancelar
          </Button>
          <ConfirmButton pending={publish.isPending} onClick={send}>
            Continuar
          </ConfirmButton>
        </div>
      </CardContent>
    </Card>
  );
}
