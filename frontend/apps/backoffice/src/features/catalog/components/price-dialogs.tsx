import { zodResolver } from '@hookform/resolvers/zod';
import { Button, Form } from '@restaurantpro/ui';
import { Pencil, Plus } from 'lucide-react';
import { useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { nonFieldError } from '../form-errors';
import { useCreatePriceList, useSavePrice } from '../hooks/use-catalog-commands';
import { useItems, useVariants } from '../hooks/use-catalog-queries';
import {
  newPriceSchema,
  priceListSchema,
  priceSchema,
  type NewPrice,
  type NewPriceValues,
  type Price,
  type PriceListValues,
  type PriceValues,
} from '../schemas';
import type { ItemPriceSummary, PriceListSummary } from '../types';
import { FormDialog } from './form-dialog';
import { FormFooter, SelectField, showOnFields, TextField } from './form-parts';

/** "Nova tabela de preços": a price list of the store, in the store's currency. */
export function PriceListDialog({ storeId, onCreated }: { storeId: string; onCreated: (list: PriceListSummary) => void }) {
  const [open, setOpen] = useState(false);
  return (
    <FormDialog
      title="Nova tabela de preços"
      description="Uma tabela de preços da loja (por exemplo, Sala ou Esplanada), na moeda da loja."
      open={open}
      onOpenChange={setOpen}
      trigger={
        <Button variant="outline">
          <Plus aria-hidden="true" />
          Nova tabela
        </Button>
      }
    >
      <PriceListForm
        storeId={storeId}
        onDone={(list) => {
          setOpen(false);
          if (list) onCreated(list);
        }}
      />
    </FormDialog>
  );
}

function PriceListForm({ storeId, onDone }: { storeId: string; onDone: (list?: PriceListSummary) => void }) {
  const form = useForm<PriceListValues>({ resolver: zodResolver(priceListSchema), defaultValues: { name: '' } });
  const create = useCreatePriceList(storeId);
  const onSubmit = form.handleSubmit(({ name }) =>
    create.mutate(name, { onSuccess: onDone, onError: (error) => showOnFields(form, error, ['name']) }),
  );
  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        <TextField control={form.control} name="name" label="Nome" maxLength={120} />
        <FormFooter
          submitLabel="Criar"
          pending={create.isPending}
          error={nonFieldError(create.error, ['name'])}
          rootMessage={form.formState.errors.root?.message}
          onCancel={() => onDone()}
          onRetry={() => void onSubmit()}
        />
      </form>
    </Form>
  );
}

/** "Definir preço": an item (or one of its variants) priced in this list. */
export function NewPriceDialog({ priceListId, currency }: { priceListId: string; currency: string }) {
  const [open, setOpen] = useState(false);
  return (
    <FormDialog
      title="Definir preço"
      description={`O preço de venda com IVA, em ${currency}, de um artigo ou de uma das suas variantes nesta tabela.`}
      open={open}
      onOpenChange={setOpen}
      trigger={
        <Button>
          <Plus aria-hidden="true" />
          Definir preço
        </Button>
      }
    >
      <NewPriceForm priceListId={priceListId} onDone={() => setOpen(false)} />
    </FormDialog>
  );
}

const NEW_PRICE_FIELDS = ['itemId', 'variantId', 'amount'] as const;

function NewPriceForm({ priceListId, onDone }: { priceListId: string; onDone: () => void }) {
  const form = useForm<NewPriceValues, unknown, NewPrice>({
    resolver: zodResolver(newPriceSchema),
    defaultValues: { itemId: '', variantId: '', amount: '' },
  });
  const save = useSavePrice(priceListId);
  const items = useItems();
  const variants = useVariants(useWatch({ control: form.control, name: 'itemId' }));
  const onSubmit = form.handleSubmit(({ itemId, variantId, amount }) =>
    save.mutate(
      { body: { itemId, amount, ...(variantId ? { variantId } : {}) } },
      { onSuccess: onDone, onError: (error) => showOnFields(form, error, NEW_PRICE_FIELDS) },
    ),
  );
  const itemOptions = (items.data?.content ?? []).map((item) => ({ value: item.id ?? '', label: `${item.name} (${item.code})` }));
  const variantOptions = (variants.data ?? []).map((variant) => ({ value: variant.id ?? '', label: variant.name ?? '' }));
  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        <SelectField control={form.control} name="itemId" label="Artigo" options={itemOptions} placeholder="Escolher artigo" />
        <SelectField control={form.control} name="variantId" label="Variante" options={variantOptions} placeholder="O próprio artigo" />
        <TextField control={form.control} name="amount" label="Preço" inputMode="decimal" className="max-w-48 tabular-nums" />
        <FormFooter
          submitLabel="Guardar"
          pending={save.isPending}
          error={nonFieldError(save.error, NEW_PRICE_FIELDS)}
          rootMessage={form.formState.errors.root?.message}
          onCancel={onDone}
          onRetry={() => void onSubmit()}
        />
      </form>
    </Form>
  );
}

/** "Alterar preço": a new amount for a price, under the version it was read at. */
export function EditPriceDialog({ priceListId, price, itemName }: { priceListId: string; price: ItemPriceSummary; itemName: string }) {
  const [open, setOpen] = useState(false);
  return (
    <FormDialog
      title="Alterar preço"
      description={`O novo preço de venda com IVA de ${itemName}. Os pedidos já confirmados mantêm o preço anterior.`}
      open={open}
      onOpenChange={setOpen}
      trigger={
        <Button variant="outline" size="sm" aria-label={`Alterar preço de ${itemName}`}>
          <Pencil aria-hidden="true" />
          Alterar
        </Button>
      }
    >
      <EditPriceForm priceListId={priceListId} price={price} onDone={() => setOpen(false)} />
    </FormDialog>
  );
}

function EditPriceForm({ priceListId, price, onDone }: { priceListId: string; price: ItemPriceSummary; onDone: () => void }) {
  const form = useForm<PriceValues, unknown, Price>({
    resolver: zodResolver(priceSchema),
    defaultValues: { amount: price.price ? String(price.price.amount) : '' },
  });
  const save = useSavePrice(priceListId);
  const onSubmit = form.handleSubmit(({ amount }) =>
    save.mutate(
      { priceId: price.id ?? '', body: { amount, version: price.version ?? 0 } },
      { onSuccess: onDone, onError: (error) => showOnFields(form, error, ['amount']) },
    ),
  );
  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        <TextField control={form.control} name="amount" label="Preço" inputMode="decimal" className="max-w-48 tabular-nums" />
        <FormFooter
          submitLabel="Guardar"
          pending={save.isPending}
          error={nonFieldError(save.error, ['amount'])}
          rootMessage={form.formState.errors.root?.message}
          onCancel={onDone}
          onRetry={() => void onSubmit()}
        />
      </form>
    </Form>
  );
}
