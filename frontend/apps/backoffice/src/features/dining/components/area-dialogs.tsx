import { zodResolver } from '@hookform/resolvers/zod';
import { Button, ConfirmButton, Form } from '@restaurantpro/ui';
import { Plus } from 'lucide-react';
import { useForm } from 'react-hook-form';
import {
  CheckboxField,
  ConflictNotice,
  EditFormDialog,
  FormDialog,
  FormError,
  RootError,
  TextField,
  useCommandForm,
  useEditBase,
} from '../../organization';
import { useCreateArea, useReadFloorItem, useUpdateArea } from '../hooks/use-floor-commands';
import { areaSchema, type AreaValues } from '../schemas';
import type { RoomAreaSummary } from '../types';
import { DINING_TAKEN } from './dining-messages';

const FIELDS = ['code', 'name', 'active'] as const;
const MESSAGES = { OCCUPIED: 'A área tem mesas abertas: feche-as antes de a desativar.' };

/** "Nova área" (§5): an area of the store, with a code unique among its areas and tables. */
export function NewAreaDialog({ storeId }: { storeId: string }) {
  const create = useCreateArea();
  return (
    <FormDialog
      title="Nova área"
      description="Uma sala, esplanada ou balcão da loja."
      trigger={
        <Button>
          <Plus aria-hidden="true" />
          Nova área
        </Button>
      }
    >
      {(close) => (
        <AreaForm
          editing={false}
          submitLabel="Criar área"
          defaultValues={{ code: '', name: '', active: true }}
          onCancel={close}
          onSubmit={async ({ code, name }) => {
            await create.mutateAsync({ storeId, code, name });
            close();
          }}
        />
      )}
    </FormDialog>
  );
}

/** "Editar área": its code, name and whether it is active, at the version read. */
export function EditAreaDialog({ area }: { area: RoomAreaSummary }) {
  return (
    <EditFormDialog title="Editar área" description={area.name} label={`Editar área ${area.name}`}>
      {(close) => <EditArea area={area} onDone={close} />}
    </EditFormDialog>
  );
}

function EditArea({ area, onDone }: { area: RoomAreaSummary; onDone: () => void }) {
  const update = useUpdateArea();
  const read = useReadFloorItem();
  const { base, reloaded, save } = useEditBase(area);
  return (
    <>
      <ConflictNotice
        shown={reloaded}
        text="A área foi alterada por outra pessoa entretanto. Estes são os valores atuais: reveja-os e guarde de novo."
      />
      <AreaForm
        key={base.version}
        editing
        submitLabel="Guardar"
        defaultValues={{ code: base.code, name: base.name, active: base.active }}
        onCancel={onDone}
        onSubmit={(values) =>
          save(
            async ({ id, version }) => {
              await update.mutateAsync({ id, body: { ...values, version } });
              onDone();
            },
            () => read.area(area.id),
          )
        }
      />
    </>
  );
}

type AreaFormProps = {
  defaultValues: AreaValues;
  editing: boolean;
  submitLabel: string;
  onSubmit: (values: AreaValues) => Promise<unknown>;
  onCancel: () => void;
};

function AreaForm({ defaultValues, editing, submitLabel, onSubmit, onCancel }: AreaFormProps) {
  const form = useForm<AreaValues>({ resolver: zodResolver(areaSchema), defaultValues });
  const { error, submit } = useCommandForm(form, FIELDS, DINING_TAKEN);
  const handle = submit(onSubmit);
  const { control } = form;
  return (
    <Form {...form}>
      <form onSubmit={(event) => void handle(event)} noValidate className="flex flex-col gap-4">
        <div className="grid gap-4 sm:grid-cols-3">
          <TextField control={control} name="code" label="Código" maxLength={20} />
          <TextField control={control} name="name" label="Nome" maxLength={120} className="sm:col-span-2" />
        </div>
        {editing && <CheckboxField control={control} name="active" label="Área ativa" />}
        <RootError message={form.formState.errors.root?.message} />
        <FormError error={error} messages={MESSAGES} />
        <div className="flex flex-wrap justify-end gap-2">
          <Button type="button" variant="outline" onClick={onCancel}>
            Cancelar
          </Button>
          <ConfirmButton type="submit" pending={form.formState.isSubmitting}>
            {submitLabel}
          </ConfirmButton>
        </div>
      </form>
    </Form>
  );
}
