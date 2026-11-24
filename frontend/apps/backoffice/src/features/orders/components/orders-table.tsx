import {
  Button,
  Money,
  StatusBadge,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { businessDate, storeTime } from '../format';
import { lifecycles, modes } from '../labels';
import type { OrderRow } from '../types';
import { ScrollTable } from './scroll-table';

type OrdersTableProps = {
  orders: OrderRow[];
  timeZone: string | undefined;
  selectedId: string | undefined;
  onSelect: (orderId: string) => void;
};

/** One row per order: number, mode, table, guests, state and day, and what is due, all as the server sends them. */
export function OrdersTable({ orders, timeZone, selectedId, onSelect }: OrdersTableProps) {
  return (
    <ScrollTable label="Pedidos">
      <TableHeader>
        <TableRow>
          <TableHead>Pedido</TableHead>
          <TableHead>Modo</TableHead>
          <TableHead>Mesa</TableHead>
          <TableHead>Pessoas</TableHead>
          <TableHead>Estado</TableHead>
          <TableHead>Dia</TableHead>
          <TableHead>Aberto</TableHead>
          <TableHead>Fechado</TableHead>
          <TableHead className="text-right">A pagar</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {orders.map((order) => (
          <TableRow key={order.id} aria-selected={order.id === selectedId} className="aria-selected:bg-muted">
            <TableCell>
              <Button
                variant="link"
                className="h-auto p-0 font-mono font-medium"
                aria-label={`Ver pedido #${order.orderNumber}`}
                onClick={() => onSelect(order.id)}
              >
                #{order.orderNumber}
              </Button>
            </TableCell>
            <TableCell>{modes[order.mode]}</TableCell>
            <TableCell className="font-mono">{order.tableCode ?? '—'}</TableCell>
            <TableCell className="tabular-nums">{order.guests ?? '—'}</TableCell>
            <TableCell>
              <StatusBadge look="tint" status={order.lifecycle} statuses={lifecycles} />
            </TableCell>
            <TableCell className="tabular-nums">{businessDate(order.businessDay)}</TableCell>
            <TableCell className="tabular-nums">{storeTime(order.openedAt, timeZone)}</TableCell>
            <TableCell className="tabular-nums">{storeTime(order.closedAt, timeZone)}</TableCell>
            <TableCell className="text-right font-semibold">
              <Money value={order.due} />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </ScrollTable>
  );
}
