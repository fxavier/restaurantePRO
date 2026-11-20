import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  DataState,
  StatusBadge,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  type StatusMap,
} from '@restaurantpro/ui';
import { CircleCheck, TriangleAlert } from 'lucide-react';
import { useState } from 'react';
import { formatQuantity } from '../format';
import { useStock, useWarehouses } from '../hooks/use-inventory-reads';
import { Amount } from './amount';

const levels: StatusMap<'LOW' | 'OK'> = {
  LOW: { label: 'Abaixo do mínimo', icon: TriangleAlert, tone: 'warning' },
  OK: { label: 'Normal', icon: CircleCheck, tone: 'success' },
};

/** "Stock": a warehouse's balances, with its minimum and value (§5 inventory). */
export function StockTab({ storeId, currency }: { storeId: string; currency: string | undefined }) {
  const warehouses = useWarehouses(storeId);
  const [chosen, setChosen] = useState<string>();
  const warehouseId = chosen ?? warehouses.data?.[0]?.id;
  const stock = useStock(warehouseId, false, 0);
  const rows = stock.data?.rows;

  return (
    <Card className="gap-4">
      <CardHeader>
        <CardTitle className="text-lg">Stock por armazém</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <label className="flex w-fit flex-col gap-1 text-sm font-medium">
          Armazém
          <select
            className="h-9 rounded-md border border-input bg-muted px-3 text-sm"
            value={warehouseId ?? ''}
            onChange={(event) => setChosen(event.target.value)}
          >
            {warehouses.data?.map((warehouse) => (
              <option key={warehouse.id} value={warehouse.id}>
                {warehouse.name}
              </option>
            ))}
          </select>
        </label>
        <DataState state={stock.isPending ? 'loading' : rows?.length ? 'ready' : 'empty'} emptyMessage="Sem stock neste armazém.">
          <Table>
            <TableCaption className="sr-only">Stock do armazém</TableCaption>
            <TableHeader>
              <TableRow>
                <TableHead>Artigo</TableHead>
                <TableHead className="text-right">Quantidade</TableHead>
                <TableHead className="text-right">Mínimo</TableHead>
                <TableHead className="text-right">Custo médio</TableHead>
                <TableHead className="text-right">Valor</TableHead>
                <TableHead>Estado</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows?.map((row) => (
                <TableRow key={row.stockItemId}>
                  <TableCell>
                    <span className="block font-medium">{row.name}</span>
                    <span className="block font-mono text-xs text-muted-foreground">{row.code}</span>
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{formatQuantity(row.quantity, row.baseUnit)}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatQuantity(row.minQuantity, row.baseUnit)}</TableCell>
                  <TableCell className="text-right">
                    <Amount value={row.averageCost} currency={currency} />
                  </TableCell>
                  <TableCell className="text-right">
                    <Amount value={row.value} currency={currency} />
                  </TableCell>
                  <TableCell>
                    <StatusBadge status={row.belowMinimum ? 'LOW' : 'OK'} statuses={levels} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </DataState>
      </CardContent>
    </Card>
  );
}
