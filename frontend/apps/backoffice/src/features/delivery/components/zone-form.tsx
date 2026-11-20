import { zodResolver } from '@hookform/resolvers/zod';
import { ConfirmButton, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, Input } from '@restaurantpro/ui';
import { useState, type ReactNode } from 'react';
import { useForm } from 'react-hook-form';
import { useFeeItems } from '../hooks/use-fee-items';
import { useCreateZone } from '../hooks/use-zone-commands';
import { zoneSchema, type ZoneValues } from '../schemas';
import type { ZoneCreateRequest } from '../types';
import { intentFor, toCreateRequest, zoneValues, type Intent } from '../zone-requests';
import { NativeSelect } from './fee-select';

type ZoneFormProps = {
  storeId: string;
  onDone: () => void;
  /** Further actions beside the submit button (Cancelar). */
  children?: ReactNode;
};

/** A new delivery zone of the store: its code, name and the FEE item its orders are charged (D128). */
export function ZoneForm({ storeId, onDone, children }: ZoneFormProps) {
  const form = useForm<ZoneValues>({ resolver: zodResolver(zoneSchema), defaultValues: zoneValues() });
  const create = useCreateZone();
  const feeItems = useFeeItems();
  const [intent, setIntent] = useState<Intent<ZoneCreateRequest>>();

  const send = (body: ZoneCreateRequest) => {
    const current = intentFor(intent, body, () => crypto.randomUUID());
    setIntent(current);
    create.mutate({ body, idempotencyKey: current.key }, { onSuccess: onDone });
  };
  const onSubmit = form.handleSubmit((values) => send(toCreateRequest(storeId, values)));

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
        <FormField
          control={form.control}
          name="feeItemId"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Artigo de taxa</FormLabel>
              <FormControl>
                <NativeSelect {...field}>
                  <option value="">Escolher artigo</option>
                  {feeItems.data?.map((item) => (
                    <option key={item.id} value={item.id}>
                      {`${item.name} (${item.code})`}
                    </option>
                  ))}
                </NativeSelect>
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
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
