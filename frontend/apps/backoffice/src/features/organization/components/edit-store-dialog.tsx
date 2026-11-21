import { Button } from '@restaurantpro/ui';
import { useEditBase } from '../hooks/use-edit-base';
import { useReadStore, useUpdateStore } from '../hooks/use-store-commands';
import { cutoffLabel, toCutoff } from '../store-values';
import type { StoreSummary } from '../types';
import { ConflictNotice } from './conflict-notice';
import { EditFormDialog } from './form-dialog';
import { StoreForm } from './store-form';

/** "Editar loja" (§5): the store's settings at the version it was read. */
export function EditStoreDialog({ store }: { store: StoreSummary }) {
  return (
    <EditFormDialog title="Editar loja" description={store.name} label={`Editar ${store.name}`}>
      {(close) => <EditStore store={store} onDone={close} />}
    </EditFormDialog>
  );
}

function EditStore({ store, onDone }: { store: StoreSummary; onDone: () => void }) {
  const update = useUpdateStore();
  const read = useReadStore();
  const { base, reloaded, save } = useEditBase(store);
  return (
    <>
      <ConflictNotice
        shown={reloaded}
        text="Foi alterada por outra pessoa entretanto. Estes são os valores atuais: reveja-os e guarde de novo."
      />
      <StoreForm
        key={base.version}
        editing
        submitLabel="Guardar"
        defaultValues={{
          code: base.code,
          name: base.name,
          timezone: base.timezone,
          businessDayCutoff: cutoffLabel(base.businessDayCutoff),
          active: base.active,
        }}
        onSubmit={({ businessDayCutoff, ...values }) =>
          save(
            async ({ id, version }) => {
              await update.mutateAsync({ id, body: { ...values, businessDayCutoff: toCutoff(businessDayCutoff)!, version } });
              onDone();
            },
            () => read(store.id),
          )
        }
      >
        <Button type="button" variant="outline" onClick={onDone}>
          Cancelar
        </Button>
      </StoreForm>
    </>
  );
}
