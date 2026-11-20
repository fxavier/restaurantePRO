import { DataState } from '@restaurantpro/ui';
import { usePrinters } from '../hooks/use-printers';

/** The store's production setup: printers, routing and the print queue (§5, §8.6). */
export function ProductionScreen({ storeId }: { storeId: string }) {
  const printers = usePrinters(storeId);
  return (
    <DataState state={printers.isPending ? 'loading' : 'ready'}>
      <ul aria-label="Impressoras da loja">
        {printers.data?.map((printer) => (
          <li key={printer.id} aria-label={printer.name}>
            {printer.name}
            <span>
              {printer.host}:{printer.port}
            </span>
          </li>
        ))}
      </ul>
    </DataState>
  );
}
