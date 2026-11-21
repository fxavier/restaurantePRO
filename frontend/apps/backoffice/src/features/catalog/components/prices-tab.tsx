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
import { useItems, usePriceLists, usePrices, useVariants } from '../hooks/use-catalog-queries';
import type { ItemPriceSummary, ItemSummary, PriceListSummary } from '../types';
import { LoadError } from './load-error';
import { EditPriceDialog, NewPriceDialog, PriceListDialog } from './price-dialogs';
import { ForStore } from './store-required';

/** "Preços": the store's price list (the schema allows one per store) and its prices as the server holds them. */
export function PricesTab({ storeId }: { storeId: string | undefined }) {
  return <ForStore storeId={storeId} what="a tabela de preços">{(id) => <StorePrices storeId={id} />}</ForStore>;
}

function StorePrices({ storeId }: { storeId: string }) {
  const lists = usePriceLists(storeId);
  if (lists.isError) {
    return <LoadError error={lists.error} onRetry={() => void lists.refetch()} forbidden="Sem permissão para ver os preços desta loja." />;
  }
  const list = lists.data?.[0];
  if (list) return <PriceListPrices key={list.id} list={list} />;
  return (
    <Card>
      <CardContent className="flex flex-wrap items-center gap-2">
        <DataState state={lists.isPending ? 'loading' : 'empty'} emptyMessage="Esta loja ainda não tem tabela de preços.">
          {null}
        </DataState>
        {lists.data && (
          <div className="ml-auto">
            <PriceListDialog storeId={storeId} />
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function PriceListPrices({ list }: { list: PriceListSummary }) {
  const listId = list.id ?? '';
  const prices = usePrices(listId);
  const items = useItems();
  const rows = prices.data ?? [];
  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle className="text-lg">Tabela {list.name}</CardTitle>
        {prices.data && <NewPriceDialog priceListId={listId} currency={list.currency ?? ''} />}
      </CardHeader>
      <CardContent>
        {prices.isError ? (
          <LoadError error={prices.error} onRetry={() => void prices.refetch()} forbidden="Sem permissão para ver estes preços." />
        ) : (
          <DataState state={prices.isPending ? 'loading' : rows.length ? 'ready' : 'empty'} emptyMessage="Esta tabela ainda não tem preços.">
            <PricesTable listId={listId} listName={list.name} prices={rows} items={items.data?.content} />
          </DataState>
        )}
      </CardContent>
    </Card>
  );
}

type PricesTableProps = { listId: string; listName: string | undefined; prices: ItemPriceSummary[]; items: ItemSummary[] | undefined };

function PricesTable({ listId, listName, prices, items }: PricesTableProps) {
  const itemOf = (id: string | undefined) => items?.find((item) => item.id === id);
  return (
    <Table>
      <TableCaption className="sr-only">Preços da tabela {listName}</TableCaption>
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
        {prices.map((price) => (
          <PriceRow key={price.id} listId={listId} price={price} item={itemOf(price.itemId)} />
        ))}
      </TableBody>
    </Table>
  );
}

function PriceRow({ listId, price, item }: { listId: string; price: ItemPriceSummary; item: ItemSummary | undefined }) {
  return (
    <TableRow>
      <TableCell>
        <ItemName item={item} />
      </TableCell>
      <TableCell>{price.variantId ? <VariantName itemId={price.itemId ?? ''} variantId={price.variantId} /> : '—'}</TableCell>
      <TableCell className="text-right">{price.price ? <Money value={price.price} /> : '—'}</TableCell>
      <TableCell className="text-right">
        <EditPriceDialog priceListId={listId} price={price} itemName={item?.name ?? 'artigo'} />
      </TableCell>
    </TableRow>
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
