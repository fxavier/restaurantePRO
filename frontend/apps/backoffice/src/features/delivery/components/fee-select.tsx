import { ApiError } from '@restaurantpro/api-client';
import { FormControl, FormField, FormItem, FormLabel, FormMessage } from '@restaurantpro/ui';
import type { ComponentProps } from 'react';
import type { Control } from 'react-hook-form';
import { errorMessage } from '../../../lib/error-messages';
import { useFeeItems } from '../hooks/use-fee-items';
import type { ZoneValues } from '../schemas';

/** The native select of the zone form, in the field tokens of `Input` (a picker of a few options, no search). */
export function NativeSelect(props: ComponentProps<'select'>) {
  return (
    <select
      {...props}
      className="h-9 w-full rounded-md border border-input bg-muted px-3 text-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:opacity-50 aria-invalid:border-destructive"
    />
  );
}

/** Why the fee items are not there: the catalog's refusal, or the general reason (no connection, an error). */
function itemsError(error: unknown): string {
  if (error instanceof ApiError && error.status === 403) return 'Sem permissão para ver os artigos do catálogo.';
  return `Não foi possível carregar os artigos de taxa: ${errorMessage(error)}`;
}

/** The zone's fee: one of the catalog's FEE items (D128). */
export function FeeItemField({ control }: { control: Control<ZoneValues> }) {
  const feeItems = useFeeItems();
  return (
    <FormField
      control={control}
      name="feeItemId"
      render={({ field }) => (
        <FormItem>
          <FormLabel>Artigo de taxa</FormLabel>
          <FormControl>
            <NativeSelect {...field} disabled={feeItems.isPending}>
              <option value="">{feeItems.isPending ? 'A carregar…' : 'Escolher artigo'}</option>
              {feeItems.data?.map((item) => (
                <option key={item.id} value={item.id}>
                  {`${item.name} (${item.code})`}
                </option>
              ))}
            </NativeSelect>
          </FormControl>
          {feeItems.isError && <p className="text-sm text-error">{itemsError(feeItems.error)}</p>}
          <FormMessage />
        </FormItem>
      )}
    />
  );
}
