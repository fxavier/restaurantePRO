import {
  Button,
  DataState,
  StatusBadge,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { dataState } from '../data-state';
import { useCustomerSearch } from '../hooks/use-customer-queries';
import { customerStatuses } from '../labels';

/** The candidates of one search: name, tax id and phones, so the user tells two namesakes apart. */
export function SearchResults({ q, onSelect }: { q: string; onSelect: (customerId: string) => void }) {
  const search = useCustomerSearch(q);
  return (
    <DataState
      state={dataState(search, (rows) => rows.length === 0)}
      onRetry={() => void search.refetch()}
      emptyMessage="Nenhum cliente encontrado."
    >
      <Table>
        <TableCaption className="sr-only">Resultados da pesquisa</TableCaption>
        <TableHeader>
          <TableRow>
            <TableHead>Cliente</TableHead>
            <TableHead>NIF</TableHead>
            <TableHead>Telefones</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {search.data?.map((customer) => (
            <TableRow key={customer.id}>
              <TableCell>
                <Button
                  variant="link"
                  className="h-auto p-0 text-left font-medium whitespace-normal"
                  onClick={() => customer.id && onSelect(customer.id)}
                >
                  {customer.name}
                </Button>
                {customer.active === false && (
                  <span className="ml-2 inline-flex">
                    <StatusBadge status="INACTIVE" statuses={customerStatuses} look="tint" />
                  </span>
                )}
              </TableCell>
              <TableCell className="font-mono">{customer.taxId}</TableCell>
              <TableCell className="font-mono">{customer.phones?.join(', ')}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </DataState>
  );
}
