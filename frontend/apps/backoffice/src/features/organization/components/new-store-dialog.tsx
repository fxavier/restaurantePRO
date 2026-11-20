import { Button, Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from '@restaurantpro/ui';
import { Plus } from 'lucide-react';
import { useState } from 'react';
import { useCreateStore } from '../hooks/use-store-commands';
import { toCutoff } from '../store-values';
import { StoreForm } from './store-form';

/** "Nova loja" (§5); the form is mounted only while open, so each opening starts empty. */
export function NewStoreDialog({ defaultTimeZone }: { defaultTimeZone: string }) {
  const [open, setOpen] = useState(false);
  const create = useCreateStore();
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button>
          <Plus aria-hidden="true" />
          Nova loja
        </Button>
      </DialogTrigger>
      <DialogContent showCloseButton={false} className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>Nova loja</DialogTitle>
          <DialogDescription>Uma loja da empresa, com a moeda da empresa.</DialogDescription>
        </DialogHeader>
        <StoreForm
          editing={false}
          submitLabel="Criar loja"
          defaultValues={{ code: '', name: '', timezone: defaultTimeZone, businessDayCutoff: '', active: true }}
          onSubmit={async ({ code, name, timezone, businessDayCutoff }) => {
            await create.mutateAsync({ code, name, timezone, businessDayCutoff: toCutoff(businessDayCutoff) });
            setOpen(false);
          }}
        >
          <Button type="button" variant="outline" onClick={() => setOpen(false)}>
            Cancelar
          </Button>
        </StoreForm>
      </DialogContent>
    </Dialog>
  );
}
