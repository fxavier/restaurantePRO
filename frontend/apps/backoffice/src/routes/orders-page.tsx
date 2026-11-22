import { ReceiptText } from 'lucide-react';
import { useStoreSelection } from '../features/organization';

/** `/orders?storeId=…&orderId=…` (§5 "Pedidos"): the store's orders, searched by period, state, mode, number or table. */
export function OrdersPage() {
  const { storeId } = useStoreSelection();
  return (
    <>
      <p className="w-fit rounded-sm bg-primary/15 px-2 py-0.5 text-xs font-semibold tracking-wide text-primary uppercase">
        Pedidos
      </p>
      <h1 className="mt-2 text-2xl font-semibold">Pedidos</h1>
      <p className="mt-1 text-muted-foreground">Os pedidos da loja, com as linhas, as rondas e os descontos de cada um.</p>
      {storeId ? null : (
        <div className="mt-6 flex flex-col items-center gap-3 rounded-xl border bg-card p-8 text-center">
          <ReceiptText aria-hidden="true" className="size-8 text-muted-foreground" />
          <p>Escolha uma loja na barra superior para ver os seus pedidos.</p>
        </div>
      )}
    </>
  );
}
