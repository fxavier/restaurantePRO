import { zodResolver } from '@hookform/resolvers/zod';
import { ConfirmButton, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, Input } from '@restaurantpro/ui';
import type { ReactNode } from 'react';
import { useForm } from 'react-hook-form';
import { useCommandForm } from '../hooks/use-command-form';
import { storeSchema, storeUpdateSchema, type StoreValues } from '../store-schemas';
import { FormError, RootError } from './form-error';

type StoreFormProps = {
  defaultValues: StoreValues;
  /** An edit: the cutoff is required and the store can be deactivated. */
  editing: boolean;
  submitLabel: string;
  /** Sends the values; a rejection is shown on the fields or as the form's error. */
  onSubmit: (values: StoreValues) => Promise<unknown>;
  /** Further actions beside the submit button (Cancelar). */
  children?: ReactNode;
};

const FIELDS = ['code', 'name', 'timezone', 'businessDayCutoff', 'active'] as const;
const TAKEN = { STORE_CODE_TAKEN: { field: 'code', message: 'Já existe uma loja com este código.' } } as const;
const TIME_ZONES = Intl.supportedValuesOf('timeZone');

/** A store's code (unique in the company), name, timezone and business-day cutoff (§5); on an edit, whether it is active. */
export function StoreForm({ defaultValues, editing, submitLabel, onSubmit, children }: StoreFormProps) {
  const form = useForm<StoreValues>({ resolver: zodResolver(editing ? storeUpdateSchema : storeSchema), defaultValues });
  const { error, submit } = useCommandForm(form, FIELDS, TAKEN);
  const handle = submit(onSubmit);

  return (
    <Form {...form}>
      <form onSubmit={(event) => void handle(event)} noValidate className="flex flex-col gap-4">
        <div className="grid gap-4 sm:grid-cols-3">
          <FormField
            control={form.control}
            name="code"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Código</FormLabel>
                <FormControl>
                  <Input {...field} autoComplete="off" maxLength={20} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
          <FormField
            control={form.control}
            name="name"
            render={({ field }) => (
              <FormItem className="sm:col-span-2">
                <FormLabel>Nome</FormLabel>
                <FormControl>
                  <Input {...field} autoComplete="off" maxLength={120} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
        </div>
        <div className="grid gap-4 sm:grid-cols-3">
          <FormField
            control={form.control}
            name="timezone"
            render={({ field }) => (
              <FormItem className="sm:col-span-2">
                <FormLabel>Fuso horário</FormLabel>
                <FormControl>
                  <Input {...field} autoComplete="off" maxLength={64} list="store-time-zones" />
                </FormControl>
                <datalist id="store-time-zones">
                  {TIME_ZONES.map((zone) => (
                    <option key={zone} value={zone} />
                  ))}
                </datalist>
                <FormMessage />
              </FormItem>
            )}
          />
          <FormField
            control={form.control}
            name="businessDayCutoff"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Fecho do dia</FormLabel>
                <FormControl>
                  <Input {...field} type="time" />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
        </div>
        <p className="-mt-2 text-sm text-muted-foreground">
          O dia de negócio muda à hora de fecho, no fuso horário da loja (pode passar da meia-noite).
        </p>
        {editing && (
          <FormField
            control={form.control}
            name="active"
            render={({ field }) => (
              <FormItem className="flex flex-row items-center gap-2">
                <FormControl>
                  <input
                    type="checkbox"
                    checked={field.value}
                    onChange={(event) => field.onChange(event.target.checked)}
                    onBlur={field.onBlur}
                    ref={field.ref}
                    name={field.name}
                    className="size-5 accent-primary"
                  />
                </FormControl>
                <FormLabel>Loja ativa</FormLabel>
              </FormItem>
            )}
          />
        )}
        <RootError message={form.formState.errors.root?.message} />
        <FormError error={error} />
        <div className="flex flex-wrap justify-end gap-2">
          {children}
          <ConfirmButton type="submit" pending={form.formState.isSubmitting}>
            {submitLabel}
          </ConfirmButton>
        </div>
      </form>
    </Form>
  );
}
