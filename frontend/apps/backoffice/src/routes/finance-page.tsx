import { Store } from 'lucide-react';
import { FinanceScreen } from '../features/finance';
import { useStoreSelection } from '../features/organization';

/** `/finance?storeId=…&tab=…` (§5, Stitch "Financeiro, Fechos & Auditoria"): the store's figures, closings, fiscal and audit. */
export function FinancePage() {
  const { storeId } = useStoreSelection();
  return (
    <>
      <p className="w-fit rounded-full bg-success-tint px-2.5 py-0.5 text-xs font-semibold tracking-wide text-success-text uppercase">
        Financeiro
      </p>
      <h1 className="mt-2 text-2xl font-semibold">Financeiro, Fechos & Auditoria</h1>
      <p className="mt-1 text-muted-foreground">
        A faturação, a aprovação dos fechos de caixa, a reconciliação, os documentos fiscais e a auditoria da loja.
      </p>
      {storeId ? (
        <FinanceScreen key={storeId} storeId={storeId} />
      ) : (
        <div className="mt-6 flex flex-col items-center gap-3 rounded-2xl border bg-card p-8 text-center">
          <Store aria-hidden="true" className="size-8 text-muted-foreground" />
          <p>Escolha uma loja na barra superior para ver o seu financeiro.</p>
        </div>
      )}
    </>
  );
}
