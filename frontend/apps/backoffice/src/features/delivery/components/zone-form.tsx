import { zodResolver } from '@hookform/resolvers/zod';
import { ConfirmButton, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, Input } from '@restaurantpro/ui';
import type { ReactNode } from 'react';
import { useForm } from 'react-hook-form';
import { errorCode } from '../../../lib/errors';
import { useSaveZone } from '../hooks/use-save-zone';
import { zoneSchema, type ZoneValues } from '../schemas';
import type { ZoneView } from '../types';
import { onFields } from '../zone-errors';
import { zoneValues } from '../zone-requests';
import { CommandError } from './command-error';
import { FeeFields } from './fee-select';

type ZoneFormProps = {
  storeId: string;
  /** The zone being edited, as last read; none for a new zone. */
  zone?: ZoneView;
  submitLabel: string;
  onDone: () => void;
  /** Someone changed the zone meanwhile (409 VERSION_CONFLICT): the caller shows the zone as it now is. */
  onConflict?: () => void;
  /** Further actions beside the submit button (Cancelar). */
  children?: ReactNode;
};

/**
 * A delivery zone of the store: its code (fixed once created), name, the FEE item its orders are charged (D128) and,
 * for an existing zone, whether it is active. Server field errors land on their fields, a taken code on the code
 * field; "Sem ligação" offers the same request again.
 */
export function ZoneForm({ storeId, zone, submitLabel, onDone, onConflict, children }: ZoneFormProps) {
  const form = useForm<ZoneValues>({ resolver: zodResolver(zoneSchema), defaultValues: zoneValues(zone) });
  const { save, error, isPending } = useSaveZone(storeId, zone);

  const onSubmit = form.handleSubmit((values) =>
    save(values, {
      onSuccess: onDone,
      onError: (failure) => {
        if (errorCode(failure) === 'VERSION_CONFLICT') onConflict?.();
        onFields(failure)?.forEach(({ field, message }) => form.setError(field, { message }));
      },
    }),
  );
  const rootMessage = form.formState.errors.root?.message;
  const generalError = error && !onFields(error) && errorCode(error) !== 'VERSION_CONFLICT';

  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        {!zone && (
          <FormField
            control={form.control}
            name="code"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Código</FormLabel>
                <FormControl>
                  <Input {...field} autoComplete="off" maxLength={20} className="max-w-48 uppercase" />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
        )}
        <FormField
          control={form.control}
          name="name"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Nome</FormLabel>
              <FormControl>
                <Input {...field} autoComplete="off" maxLength={80} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FeeFields />
        {zone && <ActiveField />}
        {rootMessage && (
          <p role="alert" className="text-sm text-error">
            {rootMessage}
          </p>
        )}
        {generalError && <CommandError error={error} pending={isPending} onRetry={() => void onSubmit()} />}
        <div className="flex flex-wrap justify-end gap-2">
          {children}
          <ConfirmButton type="submit" pending={isPending}>
            {submitLabel}
          </ConfirmButton>
        </div>
      </form>
    </Form>
  );
}

/** A retired zone stays in the deliveries' history; the POS no longer offers it (D128: zones are never deleted). */
function ActiveField() {
  return (
    <FormField
      name="active"
      render={({ field }) => (
        <FormItem className="flex flex-row items-center gap-2">
          <FormControl>
            <input
              type="checkbox"
              checked={field.value as boolean}
              onChange={(event) => field.onChange(event.target.checked)}
              onBlur={field.onBlur}
              name={field.name}
              ref={field.ref}
              className="size-5 accent-primary"
            />
          </FormControl>
          <FormLabel>Zona ativa</FormLabel>
        </FormItem>
      )}
    />
  );
}
