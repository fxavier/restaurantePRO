import { Button } from '@restaurantpro/ui';
import type { UseQueryResult } from '@tanstack/react-query';
import { FileText, Settings2, SlidersHorizontal } from 'lucide-react';
import { useState } from 'react';
import { useTestPrint } from '../hooks/use-printer-commands';
import type { Printer } from '../parse';
import type { DestinationRouteView, TerminalPrinterView } from '../types';
import { AddPrinterDialog } from './add-printer-dialog';
import { EditPrinterDialog } from './edit-printer-dialog';
import { ModeDialog } from './mode-dialog';
import { PrinterCard } from './printer-card';
import { ProblemAlert } from './problem-alert';
import { QueryState } from './query-state';

type PrinterListProps = {
  storeId: string;
  printers: UseQueryResult<Printer[]>;
  routes: DestinationRouteView[];
  assignments: TerminalPrinterView[];
  terminalName: (id: string) => string;
  /** PRODUCTION_SUPPRESS in this store: only then is Ignorar offered. */
  maySuppress: boolean;
};

/** Stitch's "Dispositivos e regras de encaminhamento": one card per printer, with its test page, mode and settings. */
export function PrinterList({ storeId, printers, routes, assignments, terminalName, maySuppress }: PrinterListProps) {
  const testPrint = useTestPrint();
  const [editing, setEditing] = useState<string>();
  const [moding, setModing] = useState<string>();
  const all = printers.data ?? [];
  const find = (id: string | null | undefined) => all.find((printer) => printer.id === id);
  /** One test page per tap, under one key for every resend of that tap (FC §8). */
  const printTest = (printer: Printer) =>
    testPrint.mutate({ printerId: printer.id, printerName: printer.name, idempotencyKey: crypto.randomUUID() });
  const resendTest = () => testPrint.variables && testPrint.mutate(testPrint.variables);

  return (
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
      {testPrint.error ? <ProblemAlert error={testPrint.error} pending={testPrint.isPending} onRetry={resendTest} /> : null}
      <QueryState
        query={printers}
        subject="as impressoras"
        isEmpty={(data) => data.length === 0}
        emptyMessage="Esta loja ainda não tem impressoras."
      >
        {(data) => (
          <ul aria-label="Impressoras da loja" className="flex flex-col gap-4">
            {data.map((printer) => (
              <PrinterCard
                key={printer.id}
                printer={printer}
                redirectName={find(printer.redirectPrinterId)?.name}
                destinations={routes.filter((route) => route.printerId === printer.id).map((route) => route.destinationCode)}
                terminals={assignments
                  .filter((assignment) => assignment.printerId === printer.id)
                  .map((assignment) => terminalName(assignment.terminalId))}
              >
                <PrinterActions
                  printer={printer}
                  testing={testPrint.isPending}
                  onTest={() => printTest(printer)}
                  onMode={() => setModing(printer.id)}
                  onEdit={() => setEditing(printer.id)}
                />
              </PrinterCard>
            ))}
          </ul>
        )}
      </QueryState>
      <EditPrinterDialog printer={find(editing)} onClose={() => setEditing(undefined)} />
      <ModeDialog printer={find(moding)} printers={all} maySuppress={maySuppress} onClose={() => setModing(undefined)} />
    </section>
  );
}

type PrinterActionsProps = {
  printer: Printer;
  testing: boolean;
  onTest: () => void;
  onMode: () => void;
  onEdit: () => void;
};

function PrinterActions({ printer, testing, onTest, onMode, onEdit }: PrinterActionsProps) {
  return (
    <>
      <Button variant="outline" size="sm" aria-label={`Imprimir teste em ${printer.name}`} disabled={testing} onClick={onTest}>
        <FileText aria-hidden="true" />
        Imprimir teste
      </Button>
      <Button variant="outline" size="sm" aria-label={`Modo de ${printer.name}`} onClick={onMode}>
        <SlidersHorizontal aria-hidden="true" />
        Modo
      </Button>
      <Button variant="outline" size="sm" aria-label={`Configurar ${printer.name}`} onClick={onEdit}>
        <Settings2 aria-hidden="true" />
        Configurar
      </Button>
    </>
  );
}
