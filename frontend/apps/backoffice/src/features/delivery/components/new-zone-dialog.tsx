import { Button, Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from '@restaurantpro/ui';
import { Plus } from 'lucide-react';
import { useState } from 'react';
import { ZoneForm } from './zone-form';

/** "Nova zona": the form is mounted only while open, so each opening is a new intent. */
export function NewZoneDialog({ storeId }: { storeId: string }) {
  const [open, setOpen] = useState(false);
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button>
          <Plus aria-hidden="true" />
          Nova zona
        </Button>
      </DialogTrigger>
      <DialogContent showCloseButton={false}>
        <DialogHeader>
          <DialogTitle>Nova zona de entrega</DialogTitle>
          <DialogDescription>
            Uma área onde a loja entrega. A taxa é um artigo de taxa do catálogo, lançado no pedido ao abrir a entrega.
          </DialogDescription>
        </DialogHeader>
        <ZoneForm storeId={storeId} submitLabel="Criar zona" onDone={() => setOpen(false)}>
          <Button type="button" variant="outline" onClick={() => setOpen(false)}>
            Cancelar
          </Button>
        </ZoneForm>
      </DialogContent>
    </Dialog>
  );
}
