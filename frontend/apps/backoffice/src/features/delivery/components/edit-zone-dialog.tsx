import {
  Alert,
  AlertDescription,
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@restaurantpro/ui';
import { RefreshCw } from 'lucide-react';
import { useState } from 'react';
import type { ZoneView } from '../types';
import { ZoneForm } from './zone-form';

type EditZoneDialogProps = { storeId: string; zone: ZoneView | undefined; onClose: () => void };

/**
 * "Editar zona": `zone` is the list's current copy, and the form is keyed on its version, so after a 409 the refreshed
 * list shows the zone as it now is and the next save goes on the new version (FC §9).
 */
export function EditZoneDialog({ storeId, zone, onClose }: EditZoneDialogProps) {
  const [conflicted, setConflicted] = useState(false);
  const close = () => {
    setConflicted(false);
    onClose();
  };
  return (
    <Dialog open={!!zone} onOpenChange={(open) => !open && close()}>
      <DialogContent showCloseButton={false}>
        <DialogHeader>
          <DialogTitle>{`Editar zona ${zone?.code ?? ''}`}</DialogTitle>
          <DialogDescription>O código da zona não muda. Uma zona inativa deixa de ser oferecida no POS.</DialogDescription>
        </DialogHeader>
        {conflicted && (
          <Alert>
            <RefreshCw aria-hidden="true" />
            <AlertDescription>A zona foi alterada por outra pessoa. Reveja os dados atuais e guarde de novo.</AlertDescription>
          </Alert>
        )}
        {zone && (
          <ZoneForm
            key={`${zone.id}:${zone.version}`}
            storeId={storeId}
            zone={zone}
            submitLabel="Guardar"
            onDone={close}
            onConflict={() => setConflicted(true)}
          >
            <Button type="button" variant="outline" onClick={close}>
              Cancelar
            </Button>
          </ZoneForm>
        )}
      </DialogContent>
    </Dialog>
  );
}
