import { Truck } from 'lucide-react';
import { DeliveryReport } from '../features/delivery';
import { useStoreSelection } from '../features/organization';

/** `/delivery/report?storeId=…` (§4 "histórico de entregas", Stitch "Relatório & Conferência de Entregas"). */
export function DeliveryReportPage() {
  const { storeId } = useStoreSelection();
  return (
    <>
      <p className="w-fit rounded-sm bg-primary/15 px-2 py-0.5 text-xs font-semibold tracking-wide text-primary uppercase">
        Delivery
      </p>
      <h1 className="mt-2 text-2xl font-semibold">Relatório de entregas</h1>
      <p className="mt-1 text-muted-foreground">As entregas da loja por dia de negócio e por estafeta.</p>
      {storeId ? (
        <DeliveryReport key={storeId} storeId={storeId} />
      ) : (
        <div className="mt-6 flex flex-col items-center gap-3 rounded-xl border bg-card p-8 text-center">
          <Truck aria-hidden="true" className="size-8 text-muted-foreground" />
          <p>Escolha uma loja na barra superior para ver as suas entregas.</p>
        </div>
      )}
    </>
  );
}
