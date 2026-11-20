import { Button, DataState } from '@restaurantpro/ui';
import { FileText, Settings2, SlidersHorizontal } from 'lucide-react';
import { useState } from 'react';
import { useSession } from '../../identity';
import { useTestPrint } from '../hooks/use-printer-commands';
import { usePrinters } from '../hooks/use-printers';
import { useRoutes, useTerminalPrinters } from '../hooks/use-routing';
import { useStoreTerminals } from '../hooks/use-store-terminals';
import { useStoreTimeZone } from '../hooks/use-store-time-zone';
import type { Printer } from '../parse';
import { holdsIn } from '../permissions';
import { AddPrinterDialog } from './add-printer-dialog';
import { EditPrinterDialog } from './edit-printer-dialog';
import { ModeDialog } from './mode-dialog';
import { PrintQueue } from './print-queue';
import { PrinterCard } from './printer-card';
import { ProblemAlert } from './problem-alert';
import { RoutingMatrix } from './routing-matrix';
import { SectionError } from './section-error';

/** The store's production setup: printers, routing and the print queue (§5, §8.6). */
export function ProductionScreen({ storeId }: { storeId: string }) {
  const permissions = useSession().data?.permissions ?? [];
  const printers = usePrinters(storeId);
  const routes = useRoutes(storeId);
  const assignments = useTerminalPrinters(storeId);
  const terminals = useStoreTerminals(storeId);
  const testPrint = useTestPrint();
  const timeZone = useStoreTimeZone(storeId);
  const [editing, setEditing] = useState<string>();
  const [moding, setModing] = useState<string>();

  const find = (id: string | null | undefined) => printers.data?.find((printer) => printer.id === id);
  const terminalName = (id: string) => terminals.data?.find((terminal) => terminal.id === id)?.name ?? 'Terminal sem nome';
  /** One test page per tap, under one key for every resend of that tap (FC §8). */
  const printTest = (printer: Printer) =>
    testPrint.mutate({ printerId: printer.id, printerName: printer.name, idempotencyKey: crypto.randomUUID() });

  return (
    <div className="mt-6 grid items-start gap-6 lg:grid-cols-5">
    <section aria-labelledby="printers-title" className="flex flex-col gap-4 lg:col-span-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="printers-title" className="text-xl font-semibold">
          Impressoras e encaminhamento
        </h2>
        {printers.data && <AddPrinterDialog storeId={storeId} />}
      </div>
      <p role="status" className="text-sm empty:hidden">
        {testPrint.isSuccess && `Página de teste de ${testPrint.variables.printerName} enviada para a fila.`}
      </p>
      {testPrint.error ? (
        <ProblemAlert
          error={testPrint.error}
          pending={testPrint.isPending}
          onRetry={() => testPrint.variables && testPrint.mutate(testPrint.variables)}
        />
      ) : null}
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
                redirectName={find(printer.redirectPrinterId)?.name}
                destinations={(routes.data ?? [])
                  .filter((route) => route.printerId === printer.id)
                  .map((route) => route.destinationCode)}
                terminals={(assignments.data ?? [])
                  .filter((assignment) => assignment.printerId === printer.id)
                  .map((assignment) => terminalName(assignment.terminalId))}
              >
                <Button
                  variant="outline"
                  size="sm"
                  aria-label={`Imprimir teste em ${printer.name}`}
                  disabled={testPrint.isPending}
                  onClick={() => printTest(printer)}
                >
                  <FileText aria-hidden="true" />
                  Imprimir teste
                </Button>
                <Button variant="outline" size="sm" aria-label={`Modo de ${printer.name}`} onClick={() => setModing(printer.id)}>
                  <SlidersHorizontal aria-hidden="true" />
                  Modo
                </Button>
                <Button variant="outline" size="sm" aria-label={`Configurar ${printer.name}`} onClick={() => setEditing(printer.id)}>
                  <Settings2 aria-hidden="true" />
                  Configurar
                </Button>
              </PrinterCard>
            ))}
          </ul>
        </DataState>
      )}
      <EditPrinterDialog printer={find(editing)} onClose={() => setEditing(undefined)} />
      <ModeDialog
        printer={find(moding)}
        printers={printers.data ?? []}
        maySuppress={holdsIn(permissions, 'PRODUCTION_SUPPRESS', storeId)}
        onClose={() => setModing(undefined)}
      />
    </section>
    <div className="flex flex-col gap-6 lg:col-span-2">
      <PrintQueue
        storeId={storeId}
        timeZone={timeZone}
        printerName={(id) => find(id)?.name ?? 'Impressora desconhecida'}
        mayOperate={holdsIn(permissions, 'PRODUCTION_OPERATE', storeId)}
      />
    </div>
    {printers.data && (
      <RoutingMatrix
        storeId={storeId}
        printers={printers.data}
        routes={routes}
        assignments={assignments}
        terminals={terminals}
      />
    )}
    </div>
  );
}
