import { zodResolver } from '@hookform/resolvers/zod';
import { Card, CardContent, CardDescription, CardHeader, CardTitle, ConfirmButton, DataState, Form } from '@restaurantpro/ui';
import { useId, useState } from 'react';
import { useForm } from 'react-hook-form';
import { ConflictNotice, FormError, RootError, TextField, useCommandForm, isVersionConflict, queryState } from '../../organization';
import { settingsFormValues } from '../form-values';
import { useDiningSettings, useSaveDiningSettings } from '../hooks/use-dining-settings';
import { settingsSchema, type SettingsValues } from '../schemas';

const FIELDS = ['reservationWindowMinutes', 'noShowGraceMinutes'] as const;

/**
 * The store's reservation settings (P47): how long a reservation holds its tables, and how late a party may be before
 * the reservation becomes a no-show. Saved at the version read; a conflict loads the current values.
 */
export function DiningSettingsCard({ storeId }: { storeId: string }) {
  const settings = useDiningSettings(storeId);
  const save = useSaveDiningSettings(storeId);
  const [outcome, setOutcome] = useState<'saved' | 'reloaded'>();
  const titleId = useId();
  const data = settings.data;

  const submit = async ({ reservationWindowMinutes, noShowGraceMinutes }: SettingsValues) => {
    setOutcome(undefined);
    try {
      await save.mutateAsync({
        reservationWindowMinutes: Number(reservationWindowMinutes),
        noShowGraceMinutes: Number(noShowGraceMinutes),
        version: data?.version,
      });
      setOutcome('saved');
    } catch (error) {
      if (!isVersionConflict(error)) throw error;
      await settings.refetch();
      setOutcome('reloaded');
    }
  };

  return (
    <Card role="region" aria-labelledby={titleId} className="gap-4">
      <CardHeader>
        <CardTitle id={titleId} className="text-lg">
          Reservas: definições
        </CardTitle>
        <CardDescription>Quanto tempo uma reserva guarda as mesas, e o atraso tolerado antes de a dar como não comparência.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <DataState
          state={queryState(settings.error, data)}
          onRetry={() => void settings.refetch()}
        >
          <ConflictNotice
            shown={outcome === 'reloaded'}
            text="As definições foram alteradas por outra pessoa entretanto. Estes são os valores atuais: reveja-os e guarde de novo."
          />
          {data && (
            <SettingsForm
              key={data.version ?? 'default'}
              defaultValues={settingsFormValues(data)}
              saved={outcome === 'saved'}
              onSubmit={submit}
            />
          )}
        </DataState>
      </CardContent>
    </Card>
  );
}

type SettingsFormProps = { defaultValues: SettingsValues; saved: boolean; onSubmit: (values: SettingsValues) => Promise<unknown> };

function SettingsForm({ defaultValues, saved, onSubmit }: SettingsFormProps) {
  const form = useForm<SettingsValues>({ resolver: zodResolver(settingsSchema), defaultValues });
  const { error, submit } = useCommandForm(form, FIELDS);
  const handle = submit(onSubmit);
  const { control } = form;
  return (
    <Form {...form}>
      <form onSubmit={(event) => void handle(event)} noValidate className="flex flex-col gap-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField
            control={control}
            name="reservationWindowMinutes"
            label="Janela de reserva (minutos)"
            type="number"
            inputMode="numeric"
            min={1}
            max={1440}
          />
          <TextField
            control={control}
            name="noShowGraceMinutes"
            label="Tolerância de não comparência (minutos)"
            type="number"
            inputMode="numeric"
            min={0}
            max={1440}
          />
        </div>
        <RootError message={form.formState.errors.root?.message} />
        <FormError error={error} />
        <div className="flex flex-wrap items-center justify-end gap-3">
          <p role="status" className="text-sm text-muted-foreground">
            {saved && !form.formState.isDirty ? 'Definições guardadas.' : ''}
          </p>
          <ConfirmButton type="submit" pending={form.formState.isSubmitting}>
            Guardar definições
          </ConfirmButton>
        </div>
      </form>
    </Form>
  );
}
