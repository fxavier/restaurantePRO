import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  StatusBadge,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  type StatusMap,
} from '@restaurantpro/ui';
import { CircleCheck, TriangleAlert } from 'lucide-react';
import { useState } from 'react';
import { formatQuantity } from '../format';
import { STOCK_PAGE_SIZE, useStock, useWarehouses } from '../hooks/use-inventory-reads';
import { usePermission } from '../hooks/use-permission';
import { Amount } from './amount';
import { SELECT } from './command-form';
import { MovementDialog } from './movement-dialog';
import { Pages, QueryState } from './query-state';
import { ScrollTable } from './scroll-table';

const levels: StatusMap<'LOW' | 'OK'> = {
  LOW: { label: 'Abaixo do mínimo', icon: TriangleAlert, tone: 'warning' },
  OK: { label: 'Normal', icon: CircleCheck, tone: 'success' },
};

/** "Stock": a warehouse's balances, with its minimum and value, and the waste and adjustment commands (§5 inventory). */
export function StockTab({ storeId, currency }: { storeId: string; currency: string | undefined }) {
  const warehouses = useWarehouses(storeId);
  const [chosen, setChosen] = useState<string>();
  const [belowMinimum, setBelowMinimum] = useState(false);
  const [page, setPage] = useState(0);
  const warehouseId = chosen ?? warehouses.data?.[0]?.id;
  const stock = useStock(warehouseId, belowMinimum, page);
  const canMove = usePermission('INVENTORY_MOVE', storeId);
  const rows = stock.data?.rows;

  return (
    <Card className="gap-4">
      <CardHeader>
        <CardTitle className="text-lg">Stock por armazém</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-wrap items-end gap-4">
          <label className="flex w-fit flex-col gap-1 text-sm font-medium">
            Armazém
            <select
              className={SELECT}
              value={warehouseId ?? ''}
              onChange={(event) => {
                setChosen(event.target.value);
                setPage(0);
              }}
            >
              {warehouses.data?.map((warehouse) => (
                <option key={warehouse.id} value={warehouse.id}>
                  {warehouse.name}
                </option>
              ))}
            </select>
          </label>
          <label className="flex h-9 items-center gap-2 text-sm">
            <input
              type="checkbox"
              className="size-4 accent-primary"
              checked={belowMinimum}
              onChange={(event) => {
                setBelowMinimum(event.target.checked);
                setPage(0);
              }}
            />
            Só abaixo do mínimo
          </label>
          {canMove && warehouseId && (
            <div className="ml-auto flex flex-wrap gap-2">
              <MovementDialog kind="waste" warehouseId={warehouseId} />
              <MovementDialog kind="manual-adjustments" warehouseId={warehouseId} />
            </div>
          )}
        </div>
        <QueryState
          query={warehouses.isError ? warehouses : stock}
          pending={warehouses.isPending || (warehouseId !== undefined && stock.isPending)}
          empty={!rows?.length}
          emptyMessage={warehouseId ? 'Sem stock neste armazém.' : 'Esta loja ainda não tem armazéns.'}
        >
          <ScrollTable label="Stock do armazém">
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
          </ScrollTable>
          <Pages page={page} total={stock.data?.totalElements ?? 0} size={STOCK_PAGE_SIZE} onPage={setPage} />
        </QueryState>
      </CardContent>
    </Card>
  );
}
