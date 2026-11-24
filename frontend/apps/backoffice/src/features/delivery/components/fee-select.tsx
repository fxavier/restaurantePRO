import { ApiError } from '@restaurantpro/api-client';
import { FormControl, FormField, FormItem, FormLabel, FormMessage } from '@restaurantpro/ui';
import type { ComponentProps } from 'react';
import { useFormContext, useWatch } from 'react-hook-form';
import { errorMessage } from '../../../lib/error-messages';
import { useFeeItems, useItemVariants } from '../hooks/use-fee-items';
import type { ZoneValues } from '../schemas';

/** The native select of the zone form, in the field tokens of `Input` (a picker of a few options, no search). */
export function NativeSelect(props: ComponentProps<'select'>) {
  return (
    <select
      {...props}
      className="h-9 w-full rounded-xl border border-input bg-card px-3 text-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:opacity-50 aria-invalid:border-destructive"
    />
  );
}

/** Why the fee items are not there: the catalog's refusal, or the general reason (no connection, an error). */
function itemsError(error: unknown): string {
  if (error instanceof ApiError && error.status === 403) return 'Sem permissão para ver os artigos do catálogo.';
  return `Não foi possível carregar os artigos de taxa: ${errorMessage(error)}`;
}

/** The zone's fee: one of the catalog's FEE items (D128), and one of its variants when it has any. */
export function FeeFields() {
  const { control, setValue } = useFormContext<ZoneValues>();
  const feeItems = useFeeItems();
  const feeItemId = useWatch({ control, name: 'feeItemId' });
  return (
    <>
      <FormField
        control={control}
        name="feeItemId"
        render={({ field }) => (
          <FormItem>
            <FormLabel>Artigo de taxa</FormLabel>
            <FormControl>
              <NativeSelect
                {...field}
                disabled={feeItems.isPending}
                onChange={(event) => {
                  field.onChange(event);
                  // another item's variant is no variant of this one
                  setValue('feeVariantId', '');
                }}
              >
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
      <VariantField itemId={feeItemId || undefined} />
    </>
  );
}

/** Shown only for an item with variants; "Sem variante" charges the item itself. */
function VariantField({ itemId }: { itemId: string | undefined }) {
  const { control } = useFormContext<ZoneValues>();
  const variants = useItemVariants(itemId);
  if (!variants.data?.length) return null;
  return (
    <FormField
      control={control}
      name="feeVariantId"
      render={({ field }) => (
        <FormItem>
          <FormLabel>Variante</FormLabel>
          <FormControl>
            <NativeSelect {...field}>
              <option value="">Sem variante</option>
              {variants.data.map((variant) => (
                <option key={variant.id} value={variant.id}>
                  {`${variant.name} (${variant.code})`}
                </option>
              ))}
            </NativeSelect>
          </FormControl>
          <FormMessage />
        </FormItem>
      )}
    />
  );
}
