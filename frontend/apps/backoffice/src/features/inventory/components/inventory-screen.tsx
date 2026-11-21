import { useStore } from '../hooks/use-inventory-reads';
import { StockSummary } from './stock-summary';
import { StockTab } from './stock-tab';

/** The selected store's inventory (§5, Stitch "Stocks, Receitas & Compras"). */
export function InventoryScreen({ storeId }: { storeId: string }) {
  const store = useStore(storeId);
  const currency = store.data?.currency;
  return (
    <div className="mt-6 flex flex-col gap-6">
      <StockSummary storeId={storeId} currency={currency} />
      <StockTab storeId={storeId} currency={currency} />
    </div>
  );
}
