import {
  StatusBadge,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { deliveryStatuses } from '../delivery-labels';
import { timeIn } from '../report-dates';
import type { DeliveryView } from '../types';

type DeliveriesTableProps = {
  deliveries: DeliveryView[];
  timeZone: string | undefined;
  /** The courier's name when known; a courier the employee list cannot name shows as "Estafeta". */
  courierName: (employeeId: string | undefined) => string | undefined;
};

/** One row per delivery: the order, its zone, the courier, the state as the server holds it, and its times. */
export function DeliveriesTable({ deliveries, timeZone, courierName }: DeliveriesTableProps) {
  return (
    <Table>
      <TableCaption className="sr-only">Entregas</TableCaption>
      <TableHeader>
        <TableRow>
          <TableHead>Pedido</TableHead>
          <TableHead>Zona</TableHead>
          <TableHead>Estafeta</TableHead>
          <TableHead>Estado</TableHead>
          <TableHead>Prometida</TableHead>
          <TableHead>Saída</TableHead>
          <TableHead>Entregue</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {deliveries.map((delivery) => (
          <TableRow key={delivery.orderId}>
            <TableCell className="font-mono font-medium">#{delivery.orderNumber}</TableCell>
            <TableCell className="font-mono text-muted-foreground">{delivery.zoneCode}</TableCell>
            <TableCell>{courierLabel(delivery.courierEmployeeId, courierName)}</TableCell>
            <TableCell>
              {delivery.status && <StatusBadge status={delivery.status} statuses={deliveryStatuses} look="tint" />}
            </TableCell>
            <TableCell className="tabular-nums">{timeIn(delivery.promisedAt, timeZone)}</TableCell>
            <TableCell className="tabular-nums">{timeIn(delivery.dispatchedAt, timeZone)}</TableCell>
            <TableCell className="tabular-nums">{timeIn(delivery.deliveredAt, timeZone)}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

function courierLabel(employeeId: string | undefined, courierName: DeliveriesTableProps['courierName']): string {
  if (!employeeId) return '—';
  return courierName(employeeId) ?? 'Estafeta';
}
