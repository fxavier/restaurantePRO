import { MapPinned, Truck, type LucideIcon } from 'lucide-react';
import type { ComponentType } from 'react';
import { DeliveryReport } from './delivery-report';
import { DeliveryZones } from './delivery-zones';

type DeliveryPage = {
  title: string;
  description: string;
  icon: LucideIcon;
  noStoreMessage: string;
  /** The page's content for the chosen store. */
  Content: ComponentType<{ storeId: string }>;
};

export const ZONES_PAGE: DeliveryPage = {
  title: 'Zonas de entrega',
  description: 'As zonas onde a loja entrega e o artigo de taxa que cada uma cobra.',
  icon: MapPinned,
  noStoreMessage: 'Escolha uma loja na barra superior para ver as suas zonas de entrega.',
  Content: DeliveryZones,
};

export const REPORT_PAGE: DeliveryPage = {
  title: 'Relatório de entregas',
  description: 'As entregas da loja por dia de negócio e por estafeta.',
  icon: Truck,
  noStoreMessage: 'Escolha uma loja na barra superior para ver as suas entregas.',
  Content: DeliveryReport,
};

/**
 * Stitch's page header for the delivery area (the "Delivery" eyebrow, title, description) over the content for the
 * store chosen in the top bar; without one, the way to pick it.
 */
export function DeliveryPageLayout({ page, storeId }: { page: DeliveryPage; storeId: string | undefined }) {
  const { title, description, icon: Icon, noStoreMessage, Content } = page;
  return (
    <>
      <p className="w-fit rounded-sm bg-primary/15 px-2 py-0.5 text-xs font-semibold tracking-wide text-primary uppercase">
        Delivery
      </p>
      <h1 className="mt-2 text-2xl font-semibold">{title}</h1>
      <p className="mt-1 text-muted-foreground">{description}</p>
      {storeId ? (
        <Content key={storeId} storeId={storeId} />
      ) : (
        <div className="mt-6 flex flex-col items-center gap-3 rounded-xl border bg-card p-8 text-center">
          <Icon aria-hidden="true" className="size-8 text-muted-foreground" />
          <p>{noStoreMessage}</p>
        </div>
      )}
    </>
  );
}
