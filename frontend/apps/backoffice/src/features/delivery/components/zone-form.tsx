import { zodResolver } from '@hookform/resolvers/zod';
import { ConfirmButton, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, Input } from '@restaurantpro/ui';
import { useState, type ReactNode } from 'react';
import { useForm } from 'react-hook-form';
import { useCreateZone } from '../hooks/use-zone-commands';
import { zoneSchema, type ZoneValues } from '../schemas';
import type { ZoneCreateRequest } from '../types';
import { onFields } from '../zone-errors';
import { intentFor, toCreateRequest, zoneValues, type Intent } from '../zone-requests';
import { CommandError } from './command-error';
import { FeeItemField } from './fee-select';

type ZoneFormProps = {
  storeId: string;
  onDone: () => void;
  /** Further actions beside the submit button (Cancelar). */
  children?: ReactNode;
};

/**
 * A new delivery zone of the store: its code, name and the FEE item its orders are charged (D128). Server field
 * errors land on their fields, a taken code on the code field; "Sem ligação" offers the same request again.
 */
export function ZoneForm({ storeId, onDone, children }: ZoneFormProps) {
  const form = useForm<ZoneValues>({ resolver: zodResolver(zoneSchema), defaultValues: zoneValues() });
  const create = useCreateZone();
  const [intent, setIntent] = useState<Intent<ZoneCreateRequest>>();

  const send = (body: ZoneCreateRequest) => {
    const current = intentFor(intent, body, () => crypto.randomUUID());
    setIntent(current);
    create.mutate(
      { body, idempotencyKey: current.key },
      {
        onSuccess: onDone,
        onError: (error) => onFields(error)?.forEach(({ field, message }) => form.setError(field, { message })),
      },
    );
  };
  const onSubmit = form.handleSubmit((values) => send(toCreateRequest(storeId, values)));
  const rootMessage = form.formState.errors.root?.message;

  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
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
        <FeeItemField control={form.control} />
        {rootMessage && (
          <p role="alert" className="text-sm text-error">
            {rootMessage}
          </p>
        )}
        {create.error && !onFields(create.error) && (
          <CommandError error={create.error} pending={create.isPending} onRetry={() => create.variables && send(create.variables.body)} />
        )}
        <div className="flex flex-wrap justify-end gap-2">
          {children}
          <ConfirmButton type="submit" pending={create.isPending}>
            Criar zona
          </ConfirmButton>
        </div>
      </form>
    </Form>
  );
}
