import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button,
  ConfirmButton,
  DataState,
  Form,
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from '@restaurantpro/ui';
import { FileText, Plus } from 'lucide-react';
import type { ReactNode } from 'react';
import { useForm } from 'react-hook-form';
import { nonFieldError } from '../form-errors';
import { useAddBarcode, useAddVariant } from '../hooks/use-catalog-commands';
import { useBarcodes, useVariants } from '../hooks/use-catalog-queries';
import { barcodeSchema, variantSchema, type BarcodeValues, type VariantValues } from '../schemas';
import type { ItemSummary, ItemVariantSummary } from '../types';
import { CommandAlert, SelectField, showOnFields, TextField } from './form-parts';

/** Stitch's "Ficha" of an item, as a side sheet: its variants and its barcodes, each with a way to add one. */
export function ItemSheet({ item }: { item: ItemSummary }) {
  return (
    <Sheet>
      <SheetTrigger asChild>
        <Button variant="outline" size="sm" aria-label={`Ficha de ${item.name}`}>
          <FileText aria-hidden="true" />
          Ficha
        </Button>
      </SheetTrigger>
      <SheetContent className="w-full overflow-y-auto sm:max-w-lg">
        <SheetHeader>
          <SheetTitle>{item.name}</SheetTitle>
          <SheetDescription>
            <span className="font-mono">{item.code}</span> · as variantes e os códigos de barras que o POS reconhece.
          </SheetDescription>
        </SheetHeader>
        <div className="flex flex-col gap-6 px-4 pb-6">
          <ItemVariants itemId={item.id ?? ''} />
          <ItemBarcodes itemId={item.id ?? ''} />
        </div>
      </SheetContent>
    </Sheet>
  );
}

function ItemVariants({ itemId }: { itemId: string }) {
  const variants = useVariants(itemId);
  const list = variants.data ?? [];
  return (
    <Section title="Variantes">
      <SectionList
        label="Variantes"
        pending={variants.isPending}
        failed={variants.isError}
        onRetry={() => void variants.refetch()}
        empty="Este artigo não tem variantes."
        rows={list.map((variant) => ({ id: variant.id ?? '', primary: variant.name ?? '', secondary: variant.code ?? '' }))}
      />
      <AddVariantForm itemId={itemId} />
    </Section>
  );
}

const VARIANT_FIELDS = ['code', 'name'] as const;

function AddVariantForm({ itemId }: { itemId: string }) {
  const form = useForm<VariantValues>({ resolver: zodResolver(variantSchema), defaultValues: { code: '', name: '' } });
  const add = useAddVariant(itemId);
  const onSubmit = form.handleSubmit((values) =>
    add.mutate(values, { onSuccess: () => form.reset(), onError: (error) => showOnFields(form, error, VARIANT_FIELDS) }),
  );
  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="grid gap-3 sm:grid-cols-2">
        <TextField control={form.control} name="code" label="Código da variante" maxLength={30} className="uppercase" />
        <TextField control={form.control} name="name" label="Nome da variante" maxLength={120} />
        <AddFooter error={nonFieldError(add.error, VARIANT_FIELDS)} pending={add.isPending} onRetry={() => void onSubmit()}>
          Adicionar variante
        </AddFooter>
      </form>
    </Form>
  );
}

function ItemBarcodes({ itemId }: { itemId: string }) {
  const barcodes = useBarcodes(itemId);
  const variants = useVariants(itemId);
  const variantName = (id: string | undefined) => variants.data?.find((variant) => variant.id === id)?.name;
  return (
    <Section title="Códigos de barras">
      <SectionList
        label="Códigos de barras"
        pending={barcodes.isPending}
        failed={barcodes.isError}
        onRetry={() => void barcodes.refetch()}
        empty="Ainda não há códigos de barras."
        rows={(barcodes.data ?? []).map((barcode) => ({
          id: barcode.id ?? '',
          primary: barcode.code ?? '',
          secondary: variantName(barcode.variantId) ?? 'Artigo',
        }))}
      />
      <AddBarcodeForm itemId={itemId} variants={variants.data ?? []} />
    </Section>
  );
}

const BARCODE_FIELDS = ['code', 'variantId'] as const;

function AddBarcodeForm({ itemId, variants }: { itemId: string; variants: ItemVariantSummary[] }) {
  const form = useForm<BarcodeValues>({ resolver: zodResolver(barcodeSchema), defaultValues: { code: '', variantId: '' } });
  const add = useAddBarcode(itemId);
  const onSubmit = form.handleSubmit(({ code, variantId }) =>
    add.mutate(
      { code, itemId, ...(variantId ? { variantId } : {}) },
      { onSuccess: () => form.reset(), onError: (error) => showOnFields(form, error, BARCODE_FIELDS) },
    ),
  );
  const options = variants.map((variant) => ({ value: variant.id ?? '', label: variant.name ?? '' }));
  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="grid gap-3 sm:grid-cols-2">
        <TextField control={form.control} name="code" label="Código de barras" maxLength={64} className="font-mono" />
        <SelectField control={form.control} name="variantId" label="Variante do código" options={options} placeholder="O próprio artigo" />
        <AddFooter error={nonFieldError(add.error, BARCODE_FIELDS)} pending={add.isPending} onRetry={() => void onSubmit()}>
          Adicionar código
        </AddFooter>
      </form>
    </Form>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-3 rounded-lg border bg-muted/40 p-4">
      <h3 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">{title}</h3>
      {children}
    </section>
  );
}

type Row = { id: string; primary: string; secondary: string };
type SectionListProps = { label: string; pending: boolean; failed: boolean; onRetry: () => void; empty: string; rows: Row[] };

function SectionList({ label, pending, failed, onRetry, empty, rows }: SectionListProps) {
  const state = pending ? 'loading' : failed ? 'error' : rows.length ? 'ready' : 'empty';
  return (
    <DataState state={state} onRetry={onRetry} emptyMessage={empty}>
      <ul aria-label={label} className="flex flex-col divide-y">
        {rows.map((row) => (
          <li key={row.id} className="flex items-center justify-between gap-3 py-2">
            <span className="font-medium">{row.primary}</span>
            <span className="font-mono text-xs text-muted-foreground">{row.secondary}</span>
          </li>
        ))}
      </ul>
    </DataState>
  );
}

function AddFooter({ error, pending, onRetry, children }: { error: unknown; pending: boolean; onRetry: () => void; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-3 sm:col-span-2">
      {error !== undefined && <CommandAlert error={error} pending={pending} onRetry={onRetry} />}
      <ConfirmButton type="submit" variant="outline" pending={pending} className="self-end">
        <Plus aria-hidden="true" />
        {children}
      </ConfirmButton>
    </div>
  );
}
