import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  DataState,
  Money,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { useState } from 'react';
import { useItems, usePriceLists, usePrices, useVariants } from '../hooks/use-catalog-queries';
import type { ItemSummary, PriceListSummary } from '../types';
import { LoadError } from './load-error';
import { EditPriceDialog, NewPriceDialog, PriceListDialog } from './price-dialogs';
import { StoreRequired } from './store-required';

/** "Preços": the store's price lists, and the prices of the chosen one as the server holds them. */
export function PricesTab({ storeId }: { storeId: string | undefined }) {
  if (!storeId) return <StoreRequired what="as tabelas de preços" />;
  return <StorePrices key={storeId} storeId={storeId} />;
}

function StorePrices({ storeId }: { storeId: string }) {
  const lists = usePriceLists(storeId);
  const [chosenId, setChosenId] = useState<string | undefined>();
  if (lists.isError) {
    return <LoadError error={lists.error} onRetry={() => void lists.refetch()} forbidden="Sem permissão para ver os preços desta loja." />;
  }
  const all = lists.data ?? [];
  const chosen = all.find((list) => list.id === chosenId) ?? all[0];
  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardContent className="flex flex-wrap items-center gap-2">
          <DataState
            state={lists.isPending ? 'loading' : all.length ? 'ready' : 'empty'}
            emptyMessage="Esta loja ainda não tem tabelas de preços."
          >
            <div role="group" aria-label="Tabelas de preços" className="flex flex-wrap gap-2">
              {all.map((list) => (
                <button
                  key={list.id}
                  type="button"
                  aria-pressed={list.id === chosen?.id}
                  onClick={() => setChosenId(list.id)}
                  className="inline-flex h-9 items-center gap-2 rounded-md border bg-muted px-3 text-sm font-semibold focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring aria-pressed:border-primary aria-pressed:bg-primary aria-pressed:text-primary-foreground"
                >
                  {list.name}
                  <span className="text-xs">{list.currency}</span>
                </button>
              ))}
            </div>
          </DataState>
          {lists.data && (
            <div className="ml-auto">
              <PriceListDialog storeId={storeId} onCreated={(list) => setChosenId(list.id)} />
            </div>
          )}
        </CardContent>
      </Card>
      {chosen && <PriceListPrices key={chosen.id} list={chosen} />}
    </div>
  );
}

function PriceListPrices({ list }: { list: PriceListSummary }) {
  const prices = usePrices(list.id ?? '');
  const items = useItems();
  const itemOf = (id: string | undefined) => items.data?.content.find((item) => item.id === id);
  const rows = prices.data ?? [];
  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle className="text-lg">Tabela {list.name}</CardTitle>
        {prices.data && <NewPriceDialog priceListId={list.id ?? ''} currency={list.currency ?? ''} />}
      </CardHeader>
      <CardContent>
        {prices.isError ? (
          <LoadError error={prices.error} onRetry={() => void prices.refetch()} forbidden="Sem permissão para ver estes preços." />
        ) : (
          <DataState state={prices.isPending ? 'loading' : rows.length ? 'ready' : 'empty'} emptyMessage="Esta tabela ainda não tem preços.">
            <Table>
              <TableCaption className="sr-only">Preços da tabela {list.name}</TableCaption>
              <TableHeader>
                <TableRow>
                  <TableHead>Artigo</TableHead>
                  <TableHead>Variante</TableHead>
                  <TableHead className="text-right">Preço com IVA</TableHead>
                  <TableHead>
                    <span className="sr-only">Ações</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((price) => {
                  const item = itemOf(price.itemId);
                  return (
                    <TableRow key={price.id}>
                      <TableCell>
                        <ItemName item={item} />
                      </TableCell>
                      <TableCell>{price.variantId ? <VariantName itemId={price.itemId ?? ''} variantId={price.variantId} /> : '—'}</TableCell>
                      <TableCell className="text-right">{price.price ? <Money value={price.price} /> : '—'}</TableCell>
                      <TableCell className="text-right">
                        <EditPriceDialog priceListId={list.id ?? ''} price={price} itemName={item?.name ?? 'artigo'} />
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </DataState>
        )}
      </CardContent>
    </Card>
  );
}

function ItemName({ item }: { item: ItemSummary | undefined }) {
  if (!item) return <>—</>;
  return (
    <>
      <span className="block font-medium">{item.name}</span>
      <span className="font-mono text-xs text-muted-foreground">{item.code}</span>
    </>
  );
}

function VariantName({ itemId, variantId }: { itemId: string; variantId: string }) {
  const variants = useVariants(itemId);
  return <>{variants.data?.find((variant) => variant.id === variantId)?.name ?? '…'}</>;
}
