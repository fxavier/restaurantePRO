import { useSuppliers } from '../hooks/use-inventory-reads';
import { PurchaseOrdersCard } from './purchase-orders-card';
import { ReceiptsCard } from './receipts-card';
import { SuppliersCard } from './suppliers-card';

/** "Compras" (§5 purchasing): orders, goods receipts and suppliers of the store. */
export function PurchasingTab({ storeId, timeZone }: { storeId: string; timeZone: string | undefined }) {
  const suppliers = useSuppliers();
  const supplierName = (id?: string) => suppliers.data?.rows.find((supplier) => supplier.id === id)?.name ?? '—';
  return (
    <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
      <div className="flex min-w-0 flex-col gap-6 xl:col-span-2">
        <PurchaseOrdersCard storeId={storeId} supplierName={supplierName} />
        <ReceiptsCard storeId={storeId} timeZone={timeZone} supplierName={supplierName} />
      </div>
      <SuppliersCard storeId={storeId} />
    </div>
  );
}
