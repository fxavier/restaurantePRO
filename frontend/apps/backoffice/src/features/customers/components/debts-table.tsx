import {
  Button,
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
import { useState } from 'react';
import { DEBT_PAGE_SIZE, useDebts } from '../hooks/use-customer-queries';
import { dataState } from '../data-state';
import type { DebtRow } from '../types';

/** "Com saldo devedor": the customers who owe, with the balance and its age as the server computed them (§5). */
export function DebtsTable({ onSelect }: { onSelect: (customerId: string) => void }) {
  const [page, setPage] = useState(0);
  const debts = useDebts(page);
  const data = debts.data;
  const pages = data ? Math.ceil(data.totalElements / DEBT_PAGE_SIZE) : 0;
  return (
    <DataState
      state={dataState(debts, (d) => d.rows.length === 0)}
      onRetry={() => void debts.refetch()}
      emptyMessage="Nenhum cliente tem saldo devedor."
    >
      <Table>
        <TableCaption className="sr-only">Clientes com saldo devedor</TableCaption>
        <TableHeader>
          <TableRow>
            <TableHead>Cliente</TableHead>
            <TableHead className="text-right">Saldo devedor</TableHead>
            <TableHead className="text-right">Até 30 dias</TableHead>
            <TableHead className="text-right">31–60 dias</TableHead>
            <TableHead className="text-right">61–90 dias</TableHead>
            <TableHead className="text-right">Mais de 90 dias</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {data?.rows.map((row) => (
            <DebtLine key={row.customerId} row={row} onSelect={onSelect} />
          ))}
        </TableBody>
      </Table>
      {pages > 1 && <Pager page={page} pages={pages} onPage={setPage} />}
    </DataState>
  );
}

function DebtLine({ row, onSelect }: { row: DebtRow; onSelect: (customerId: string) => void }) {
  return (
    <TableRow>
      <TableCell>
        <Button
          variant="link"
          className="h-auto p-0 text-left font-medium whitespace-normal"
          onClick={() => row.customerId && onSelect(row.customerId)}
        >
          {row.name}
        </Button>
        {row.taxId && <p className="font-mono text-xs text-muted-foreground">NIF {row.taxId}</p>}
      </TableCell>
      <TableCell className="text-right font-semibold">{row.balance && <Money value={row.balance} />}</TableCell>
      <TableCell className="text-right">{row.days0To30 && <Money value={row.days0To30} />}</TableCell>
      <TableCell className="text-right">{row.days31To60 && <Money value={row.days31To60} />}</TableCell>
      <TableCell className="text-right">{row.days61To90 && <Money value={row.days61To90} />}</TableCell>
      <TableCell className="text-right">{row.over90 && <Money value={row.over90} />}</TableCell>
    </TableRow>
  );
}

function Pager({ page, pages, onPage }: { page: number; pages: number; onPage: (page: number) => void }) {
  return (
    <nav aria-label="Páginas" className="flex items-center justify-end gap-2 text-sm">
      <Button variant="outline" size="sm" disabled={page === 0} onClick={() => onPage(page - 1)}>
        Anterior
      </Button>
      <span>
        Página {page + 1} de {pages}
      </span>
      <Button variant="outline" size="sm" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>
        Seguinte
      </Button>
    </nav>
  );
}
