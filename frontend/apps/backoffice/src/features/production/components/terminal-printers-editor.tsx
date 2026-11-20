import { ConfirmButton, Form, FormControl, FormField, FormItem, FormLabel } from '@restaurantpro/ui';
import { useForm } from 'react-hook-form';
import { useReplaceTerminalPrinters } from '../hooks/use-routing';
import type { StoreTerminal } from '../hooks/use-store-terminals';
import type { Printer } from '../parse';
import type { TerminalPrinterView } from '../types';
import { NativeSelect } from './native-select';
import { ProblemAlert } from './problem-alert';

type TerminalPrintersEditorProps = {
  storeId: string;
  terminals: StoreTerminal[];
  assignments: TerminalPrinterView[];
  printers: Printer[];
};

type Values = { terminals: TerminalPrinterView[] };

/**
 * Where each POS terminal prints its subtotals and documents (CONTEXT: TerminalPrinter): one select per terminal,
 * "Sem impressora" leaving it out; the table is replaced whole. Nothing to validate beyond the select itself.
 */
export function TerminalPrintersEditor({ storeId, terminals, assignments, printers }: TerminalPrintersEditorProps) {
  const rows = terminals.map((terminal) => ({
    terminalId: terminal.id,
    printerId: assignments.find((assignment) => assignment.terminalId === terminal.id)?.printerId ?? '',
  }));
  const form = useForm<Values>({ values: { terminals: rows }, resetOptions: { keepDirtyValues: true } });
  const replace = useReplaceTerminalPrinters(storeId);
  const save = form.handleSubmit((values) => replace.mutate(values.terminals.filter((row) => row.printerId !== '')));

  return (
    <Form {...form}>
      <form aria-label="Impressora por terminal" noValidate onSubmit={(event) => void save(event)} className="flex flex-col gap-3">
        <ul className="flex flex-col gap-2">
          {terminals.map((terminal, index) => (
            <li key={terminal.id}>
              <FormField
                control={form.control}
                name={`terminals.${index}.printerId`}
                render={({ field }) => (
                  <FormItem className="grid grid-cols-2 items-center gap-2">
                    <FormLabel>{terminal.name}</FormLabel>
                    <FormControl>
                      <NativeSelect {...field}>
                        <option value="">Sem impressora</option>
                        {printers.map((printer) => (
                          <option key={printer.id} value={printer.id}>
                            {printer.name}
                          </option>
                        ))}
                      </NativeSelect>
                    </FormControl>
                  </FormItem>
                )}
              />
            </li>
          ))}
        </ul>
        {replace.error ? <ProblemAlert error={replace.error} pending={replace.isPending} onRetry={() => void save()} /> : null}
        <p role="status" className="text-sm empty:hidden">
          {replace.isSuccess && 'Impressoras dos terminais guardadas.'}
        </p>
        <div className="flex justify-end">
          <ConfirmButton type="submit" pending={replace.isPending}>
            Guardar terminais
          </ConfirmButton>
        </div>
      </form>
    </Form>
  );
}
