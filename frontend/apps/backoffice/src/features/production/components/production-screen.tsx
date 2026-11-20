import { DataState } from '@restaurantpro/ui';
import { usePrinters } from '../hooks/use-printers';
import { useRoutes, useTerminalPrinters } from '../hooks/use-routing';
import { useStoreTerminals } from '../hooks/use-store-terminals';
import { PrinterCard } from './printer-card';
import { SectionError } from './section-error';

/** The store's production setup: printers, routing and the print queue (§5, §8.6). */
export function ProductionScreen({ storeId }: { storeId: string }) {
  const printers = usePrinters(storeId);
  const routes = useRoutes(storeId);
  const assignments = useTerminalPrinters(storeId);
  const terminals = useStoreTerminals(storeId);

  const nameOf = (id: string | null | undefined) => printers.data?.find((printer) => printer.id === id)?.name;
  const terminalName = (id: string) => terminals.data?.find((terminal) => terminal.id === id)?.name ?? 'Terminal sem nome';

  return (
    <section aria-labelledby="printers-title" className="mt-6 flex flex-col gap-4">
      <h2 id="printers-title" className="text-xl font-semibold">
        Impressoras e encaminhamento
      </h2>
      {printers.isError ? (
        <SectionError error={printers.error} subject="as impressoras" onRetry={() => void printers.refetch()} />
      ) : (
        <DataState
          state={printers.isPending ? 'loading' : printers.data.length ? 'ready' : 'empty'}
          emptyMessage="Esta loja ainda não tem impressoras."
        >
          <ul aria-label="Impressoras da loja" className="flex flex-col gap-4">
            {printers.data?.map((printer) => (
              <PrinterCard
                key={printer.id}
                printer={printer}
                redirectName={nameOf(printer.redirectPrinterId)}
                destinations={(routes.data ?? [])
                  .filter((route) => route.printerId === printer.id)
                  .map((route) => route.destinationCode)}
                terminals={(assignments.data ?? [])
                  .filter((assignment) => assignment.printerId === printer.id)
                  .map((assignment) => terminalName(assignment.terminalId))}
              />
            ))}
          </ul>
        </DataState>
      )}
    </section>
  );
}
