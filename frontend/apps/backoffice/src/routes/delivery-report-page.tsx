import { DeliveryPageLayout, REPORT_PAGE } from '../features/delivery';
import { useStoreSelection } from '../features/organization';

/** `/delivery/report?storeId=…` (§4 "histórico de entregas", Stitch "Relatório & Conferência de Entregas"). */
export function DeliveryReportPage() {
  return <DeliveryPageLayout page={REPORT_PAGE} storeId={useStoreSelection().storeId} />;
}
