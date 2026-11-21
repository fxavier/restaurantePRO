import {
  DataState,
  Money,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { dataState } from '../data-state';
import { useStatement } from '../hooks/use-customer-queries';
import { formatBusinessDate, ledgerKindLabel } from '../labels';
import { QueryFailure } from './query-failure';

/** Stitch's "Extrato de Conta Corrente": each movement with the balance after it, as the server computed it. */
export function CreditStatement({ customerId }: { customerId: string }) {
  const statement = useStatement(customerId);
  if (statement.isError) return <QueryFailure error={statement.error} onRetry={() => void statement.refetch()} />;
  const data = statement.data;
  return (
    <section aria-label="Extrato" className="flex flex-col gap-3">
      <h3 className="text-lg font-semibold">Extrato de conta corrente</h3>
      <DataState state={dataState(statement, (s) => !s.lines?.length)} emptyMessage="Sem movimentos na conta corrente.">
        <Table>
          <TableCaption className="sr-only">Extrato de conta corrente</TableCaption>
          <TableHeader>
            <TableRow>
              <TableHead>Data</TableHead>
              <TableHead>Movimento</TableHead>
              <TableHead className="text-right">Valor</TableHead>
              <TableHead className="text-right">Saldo</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data?.lines?.map((line) => (
              <TableRow key={line.entryId}>
                <TableCell className="whitespace-nowrap">{formatBusinessDate(line.businessDate)}</TableCell>
                <TableCell className="whitespace-normal">
                  <span className="block">{line.description}</span>
                  <span className="block text-xs text-muted-foreground">{ledgerKindLabel(line.kind)}</span>
                </TableCell>
                <TableCell className="text-right">{line.amount && <Money value={line.amount} />}</TableCell>
                <TableCell className="text-right">{line.balance && <Money value={line.balance} />}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </DataState>
      {data?.closingBalance && (
        <p className="flex justify-end gap-2 text-sm">
          <span className="text-muted-foreground">Saldo final</span>
          <Money value={data.closingBalance} className="font-semibold" />
        </p>
      )}
    </section>
  );
}
