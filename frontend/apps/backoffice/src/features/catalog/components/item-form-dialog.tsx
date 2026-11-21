import { zodResolver } from '@hookform/resolvers/zod';
import { Button, Form, FormControl, FormField, FormItem, FormLabel } from '@restaurantpro/ui';
import { Pencil, Plus } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { DESTINATIONS, taxLabel } from '../catalog-labels';
import { nonFieldError } from '../form-errors';
import { useSaveItem } from '../hooks/use-catalog-commands';
import { useFamilies, useTaxCategories, useUnits } from '../hooks/use-catalog-queries';
import { createItemRequest, itemValues, NEW_ITEM, updateItemRequest } from '../item-request';
import { itemSchema, type ItemValues } from '../schemas';
import type { ItemSummary } from '../types';
import { CutListNotice } from './cut-list-notice';
import { FormDialog } from './form-dialog';
import { FormFooter, SelectField, showOnFields, TextField, type Option } from './form-parts';

/** "Novo artigo", or "Editar artigo" when given one. */
export function ItemFormDialog({ item }: { item?: ItemSummary }) {
  const [open, setOpen] = useState(false);
  const trigger = item ? (
    <Button variant="outline" size="sm" aria-label={`Editar ${item.name}`}>
      <Pencil aria-hidden="true" />
      Editar
    </Button>
  ) : (
    <Button>
      <Plus aria-hidden="true" />
      Novo artigo
    </Button>
  );
  return (
    <FormDialog
      title={item ? 'Editar artigo' : 'Novo artigo'}
      description="O artigo, a família onde aparece no POS, a unidade, a categoria de IVA e o destino de produção."
      open={open}
      onOpenChange={setOpen}
      trigger={trigger}
    >
      <ItemForm item={item} onDone={() => setOpen(false)} />
    </FormDialog>
  );
}

const FIELDS = ['code', 'name', 'familyId', 'unitId', 'taxCategoryId', 'productionDestination', 'availability'] as const;
const AVAILABILITY: Option[] = [
  { value: 'ACTIVE', label: 'Ativo' },
  { value: 'INACTIVE', label: 'Inativo' },
];

function ItemForm({ item, onDone }: { item?: ItemSummary; onDone: () => void }) {
  const form = useForm<ItemValues>({ resolver: zodResolver(itemSchema), defaultValues: item ? itemValues(item) : NEW_ITEM });
  const save = useSaveItem();
  const options = useItemOptions(item?.productionDestination);

  const send = (values: ItemValues) =>
    save.mutate(item?.id ? { id: item.id, body: updateItemRequest(values, item) } : { body: createItemRequest(values) }, {
      onSuccess: onDone,
      onError: (error) => showOnFields(form, error, FIELDS),
    });
  const onSubmit = form.handleSubmit(send);

  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="grid gap-4 sm:grid-cols-2">
        <div className="flex flex-col gap-2 empty:hidden sm:col-span-2">{options.notices}</div>
        <TextField control={form.control} name="code" label="Código" maxLength={30} className="uppercase" />
        <TextField control={form.control} name="name" label="Nome" maxLength={120} />
        <SelectField control={form.control} name="familyId" label="Família" options={options.families} placeholder="Escolher família" />
        <SelectField control={form.control} name="unitId" label="Unidade" options={options.units} placeholder="Escolher unidade" />
        <SelectField control={form.control} name="taxCategoryId" label="Categoria de IVA" options={options.taxCategories} placeholder="Escolher categoria" />
        <SelectField control={form.control} name="productionDestination" label="Destino de produção" options={options.destinations} placeholder="Sem destino" />
        {item && <SelectField control={form.control} name="availability" label="Estado" options={AVAILABILITY} placeholder="Escolher estado" />}
        <FormField
          control={form.control}
          name="soldByWeight"
          render={({ field }) => (
            <FormItem className="flex flex-row items-center gap-2 self-end">
              <FormControl>
                <input
                  type="checkbox"
                  checked={field.value}
                  onChange={(event) => field.onChange(event.target.checked)}
                  onBlur={field.onBlur}
                  className="size-5 accent-primary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
                />
              </FormControl>
              <FormLabel>Vendido ao peso</FormLabel>
            </FormItem>
          )}
        />
        <div className="flex flex-col gap-4 sm:col-span-2">
          <FormFooter
            submitLabel={item ? 'Guardar' : 'Criar'}
            pending={save.isPending}
            error={nonFieldError(save.error, FIELDS)}
            rootMessage={form.formState.errors.root?.message}
            onCancel={onDone}
            onRetry={() => void onSubmit()}
          />
        </div>
      </form>
    </Form>
  );
}

/** The selects' options from the reference lists; an item's unknown destination stays selectable as itself. */
function useItemOptions(currentDestination: string | undefined) {
  const families = useFamilies();
  const units = useUnits();
  const taxCategories = useTaxCategories();
  const [now] = useState(() => new Date().toISOString());
  return {
    families: namedOptions(families.data?.content),
    units: namedOptions(units.data?.content),
    taxCategories: (taxCategories.data?.content ?? []).map((category) => ({ value: category.id ?? '', label: taxLabel(category, now) })),
    destinations: destinationOptions(currentDestination),
    notices: (
      <>
        <CutListNotice list={families.data} what="famílias" />
        <CutListNotice list={units.data} what="unidades" />
        <CutListNotice list={taxCategories.data} what="categorias de IVA" />
      </>
    ),
  };
}

function namedOptions(list: readonly { id?: string; name?: string }[] | undefined): Option[] {
  return (list ?? []).map((one) => ({ value: one.id ?? '', label: one.name ?? '' }));
}

function destinationOptions(current: string | undefined): Option[] {
  const known = DESTINATIONS.map((destination) => ({ value: destination.code, label: destination.label }));
  const unknown = current && !known.some((option) => option.value === current);
  return unknown ? [...known, { value: current, label: current }] : known;
}
