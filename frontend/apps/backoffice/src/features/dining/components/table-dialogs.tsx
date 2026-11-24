import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button,
  cn,
  ConfirmButton,
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
  NativeSelect,
  TONE_CARD,
} from '@restaurantpro/ui';
import { Plus } from 'lucide-react';
import type { ReactNode } from 'react';
import { useForm } from 'react-hook-form';
import {
  CheckboxField,
  ConflictNotice,
  FormDialog,
  FormError,
  RootError,
  TextField,
  useCommandForm,
  useEditBase,
} from '../../organization';
import { tableFormValues } from '../form-values';
import { useCreateTable, useReadFloorItem, useUpdateTable } from '../hooks/use-floor-commands';
import { tableSchema, type TableValues } from '../schemas';
import type { RestaurantTableSummary, RoomAreaSummary } from '../types';
import { tableTone } from './area-card';
import { DINING_TAKEN } from './dining-messages';

const FIELDS = ['code', 'seats', 'areaId', 'active'] as const;
const MESSAGES = { OCCUPIED: 'A mesa está aberta: feche-a antes de a desativar.' };

/** "Nova mesa" in an area (§5): a code unique among the store's tables, and its seats. */
export function NewTableDialog({ area }: { area: RoomAreaSummary }) {
  const create = useCreateTable();
  return (
    <FormDialog
      title="Nova mesa"
      description={`Em ${area.name}.`}
      trigger={
        <Button variant="outline" size="sm" aria-label={`Nova mesa em ${area.name}`}>
          <Plus aria-hidden="true" />
          Nova mesa
        </Button>
      }
    >
      {(close) => (
        <TableForm
          areas={undefined}
          submitLabel="Criar mesa"
          defaultValues={{ code: '', seats: '', areaId: area.id, active: true }}
          onCancel={close}
          onSubmit={async ({ code, seats }) => {
            await create.mutateAsync({ storeId: area.storeId, areaId: area.id, code, seats: Number(seats) });
            close();
          }}
        />
      )}
    </FormDialog>
  );
}

/** "Editar mesa": its code, seats, area and whether it is active, at the version read; the trigger is the table. */
export function EditTableDialog({ table, areas, children }: { table: RestaurantTableSummary; areas: RoomAreaSummary[]; children: ReactNode }) {
  const tone = tableTone(table);
  return (
    <FormDialog
      title="Editar mesa"
      description={`Mesa ${table.code}`}
      trigger={
        // The name starts with the action and keeps the visible text (WCAG 2.5.3 label in name): "Editar mesa M01 4 lugares".
        <Button variant="outline" className={cn('h-full w-full min-w-0 flex-col items-start justify-start px-3 py-2 text-left whitespace-normal', tone && TONE_CARD[tone])}>
          <span className="sr-only">Editar mesa</span> {children}
        </Button>
      }
    >
      {(close) => <EditTable table={table} areas={areas} onDone={close} />}
    </FormDialog>
  );
}

function EditTable({ table, areas, onDone }: { table: RestaurantTableSummary; areas: RoomAreaSummary[]; onDone: () => void }) {
  const update = useUpdateTable();
  const read = useReadFloorItem();
  const { base, reloaded, save } = useEditBase(table);
  return (
    <>
      <ConflictNotice
        shown={reloaded}
        text="A mesa foi alterada por outra pessoa entretanto. Estes são os valores atuais: reveja-os e guarde de novo."
      />
      <TableForm
        key={base.version}
        areas={areas}
        submitLabel="Guardar"
        defaultValues={tableFormValues(base)}
        onCancel={onDone}
        onSubmit={({ seats, ...values }) =>
          save(
            async ({ version }) => {
              await update.mutateAsync({ id: table.id!, body: { ...values, seats: Number(seats), version: version ?? 0 } });
              onDone();
            },
            () => read.table(table.id!),
          )
        }
      />
    </>
  );
}

type TableFormProps = {
  defaultValues: TableValues;
  /** On an edit, the areas the table can move to; a new table stays in its area. */
  areas: RoomAreaSummary[] | undefined;
  submitLabel: string;
  onSubmit: (values: TableValues) => Promise<unknown>;
  onCancel: () => void;
};

function TableForm({ defaultValues, areas, submitLabel, onSubmit, onCancel }: TableFormProps) {
  const form = useForm<TableValues>({ resolver: zodResolver(tableSchema), defaultValues });
  const { error, submit } = useCommandForm(form, FIELDS, DINING_TAKEN);
  const handle = submit(onSubmit);
  const { control } = form;
  return (
    <Form {...form}>
      <form onSubmit={(event) => void handle(event)} noValidate className="flex flex-col gap-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField control={control} name="code" label="Código" maxLength={20} />
          <TextField control={control} name="seats" label="Lugares" type="number" inputMode="numeric" min={1} step={1} />
        </div>
        {areas && (
          <>
            <FormField
              control={control}
              name="areaId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Área</FormLabel>
                  <FormControl>
                    <NativeSelect {...field} className="w-auto">
                      {areas.map((area) => (
                        <option key={area.id} value={area.id}>
                          {area.active ? area.name : `${area.name} (inativa)`}
                        </option>
                      ))}
                    </NativeSelect>
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <CheckboxField control={control} name="active" label="Mesa ativa" />
          </>
        )}
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
