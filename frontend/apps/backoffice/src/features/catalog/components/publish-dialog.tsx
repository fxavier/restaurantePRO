import type { ApiError } from '@restaurantpro/api-client';
import {
  Button,
  ConfirmButton,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Label,
} from '@restaurantpro/ui';
import { ShieldCheck } from 'lucide-react';
import { useId, useState } from 'react';
import { browser } from '../../../lib/browser';
import { isStepUpRequired, savePendingCommand, stepUpUrl } from '../../../lib/step-up';
import { useTenantPin } from '../../../lib/tenant-pin';
import { usePublishRelease } from '../hooks/use-catalog-commands';
import { releasesPath, toPendingPublication, type Publication } from '../release-commands';
import { CommandAlert } from './form-parts';

type Props = { releaseId: string; storeId: string };

/**
 * "Publicar": a VALIDATED release becomes the one the store's POS sells from. Opened for `releaseId`, at the tab's
 * level, so a refreshed list (a 409 that changed the release) never closes it under the user; the content mounts per
 * opening, so each opening is a new intent.
 */
export function PublishDialog({ releaseId, storeId, onClose }: { releaseId: string | undefined; storeId: string; onClose: () => void }) {
  return (
    <Dialog open={releaseId !== undefined} onOpenChange={(open) => !open && onClose()}>
      <DialogContent showCloseButton={false}>
        <DialogHeader>
          <DialogTitle>Publicar versão</DialogTitle>
          <DialogDescription>
            Os terminais POS desta loja passam a vender a partir desta versão, e a versão publicada até agora fica
            retirada. Os pedidos e documentos já registados não mudam.
          </DialogDescription>
        </DialogHeader>
        {releaseId && <PublishForm releaseId={releaseId} storeId={storeId} onDone={onClose} />}
      </DialogContent>
    </Dialog>
  );
}

function PublishForm({ releaseId, storeId, onDone }: Props & { onDone: () => void }) {
  const noteId = useId();
  const tenantPin = useTenantPin();
  const publish = usePublishRelease(storeId);
  const [note, setNote] = useState('');
  // One intent per request (FC §8): the same note goes again under the same key; another note is a new intent.
  const [intent, setIntent] = useState<Publication | undefined>();

  const send = () => {
    const current = intent?.note === note ? intent : { releaseId, storeId, note, idempotencyKey: crypto.randomUUID() };
    setIntent(current);
    publish.mutate(current, { onSuccess: onDone });
  };
  const confirmIdentity = (error: ApiError) => {
    if (!intent) return;
    savePendingCommand(toPendingPublication(intent, tenantPin.get()?.tenantId ?? ''));
    browser.assign(stepUpUrl(error, releasesPath(storeId)));
  };

  if (isStepUpRequired(publish.error)) {
    const error = publish.error;
    return (
      <div className="flex flex-col items-center gap-3 text-center">
        <ShieldCheck aria-hidden="true" className="size-8 text-muted-foreground" />
        <p>Para publicar, confirme a sua identidade.</p>
        <p className="text-sm text-muted-foreground">
          Vai à página de início de sessão com o segundo fator e volta a esta página, com a publicação pronta a continuar.
        </p>
        <DialogFooter className="w-full">
          <Button type="button" variant="outline" onClick={onDone}>
            Cancelar
          </Button>
          <Button type="button" onClick={() => confirmIdentity(error)}>
            Confirmar identidade
          </Button>
        </DialogFooter>
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-2">
        <Label htmlFor={noteId}>Nota (opcional)</Label>
        <textarea
          id={noteId}
          value={note}
          maxLength={500}
          rows={3}
          onChange={(event) => setNote(event.target.value)}
          className="rounded-xl border border-input bg-card px-3 py-2 text-sm shadow-xs focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
        />
      </div>
      {publish.error && <CommandAlert error={publish.error} pending={publish.isPending} onRetry={send} />}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onDone}>
          Cancelar
        </Button>
        <ConfirmButton type="button" pending={publish.isPending} onClick={send}>
          Publicar
        </ConfirmButton>
      </DialogFooter>
    </div>
  );
}
