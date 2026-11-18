import { Button, Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from '@restaurantpro/ui';
import { Plus } from 'lucide-react';
import { useState } from 'react';
import { RegisterTerminalForm } from './register-terminal-form';

/** "Registar terminal" (story 13); the form is mounted only while open, so each opening is a new intent. */
export function RegisterTerminalDialog({ storeId, returnTo }: { storeId: string; returnTo: string }) {
  const [open, setOpen] = useState(false);
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button>
          <Plus aria-hidden="true" />
          Registar terminal
        </Button>
      </DialogTrigger>
      <DialogContent showCloseButton={false}>
        <DialogHeader>
          <DialogTitle>Registar terminal</DialogTitle>
          <DialogDescription>
            Um terminal POS desta loja. Depois de o registar, emita o código para o ativar no dispositivo.
          </DialogDescription>
        </DialogHeader>
        <RegisterTerminalForm storeId={storeId} returnTo={returnTo} submitLabel="Registar" onCreated={() => setOpen(false)}>
          <Button type="button" variant="outline" onClick={() => setOpen(false)}>
            Cancelar
          </Button>
        </RegisterTerminalForm>
      </DialogContent>
    </Dialog>
  );
}
