import { Button } from '@restaurantpro/ui';
import { Plus } from 'lucide-react';
import { useCreateStore } from '../hooks/use-store-commands';
import { toCutoff } from '../store-values';
import { FormDialog } from './form-dialog';
import { StoreForm } from './store-form';

/** "Nova loja" (§5): a store of the company, in the company's currency. */
export function NewStoreDialog({ defaultTimeZone }: { defaultTimeZone: string }) {
  const create = useCreateStore();
  return (
    <FormDialog
      title="Nova loja"
      description="Uma loja da empresa, com a moeda da empresa."
      trigger={
        <Button>
          <Plus aria-hidden="true" />
          Nova loja
        </Button>
      }
    >
      {(close) => (
        <StoreForm
          editing={false}
          submitLabel="Criar loja"
          defaultValues={{ code: '', name: '', timezone: defaultTimeZone, businessDayCutoff: '', active: true }}
          onSubmit={async ({ code, name, timezone, businessDayCutoff }) => {
            await create.mutateAsync({ code, name, timezone, businessDayCutoff: toCutoff(businessDayCutoff) });
            close();
          }}
        >
          <Button type="button" variant="outline" onClick={close}>
            Cancelar
          </Button>
        </StoreForm>
      )}
    </FormDialog>
  );
}
