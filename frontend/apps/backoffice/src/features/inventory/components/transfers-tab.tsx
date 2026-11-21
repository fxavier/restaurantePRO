import {
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { PackageCheck, Truck } from 'lucide-react';
import { useState } from 'react';
import { formatDateTime } from '../format';
import { useTransferAction } from '../hooks/use-inventory-commands';
import { LIST_PAGE_SIZE, useTransfers, useWarehouses } from '../hooks/use-inventory-reads';
import { usePermission } from '../hooks/use-permission';
import { useRowCommand } from '../hooks/use-row-command';
import type { TransferView } from '../types';
import { CommandError } from './command-error';
import { Pages, QueryState } from './query-state';
import { StateBadge, transferStates } from './state-badge';

/**
 * "Transferências" (§5 inventory, D120): the store's transfers between warehouses; ship a draft, receive a shipped
 * one as shipped. Creating a transfer and a partial receipt are follow-ups.
 */
export function TransfersTab({ storeId, timeZone }: { storeId: string; timeZone: string | undefined }) {
  const [page, setPage] = useState(0);
  const transfers = useTransfers(storeId, page);
  const warehouses = useWarehouses(storeId);
  const action = useRowCommand(useTransferAction());
  const canTransfer = usePermission('INVENTORY_TRANSFER', storeId);
  const rows = transfers.data?.rows;
  const warehouse = (id?: string) => warehouses.data?.find((candidate) => candidate.id === id)?.name ?? 'Outra loja';
  const route = (transfer: TransferView) => `${warehouse(transfer.fromWarehouseId)} → ${warehouse(transfer.toWarehouseId)}`;

  return (
    <Card className="gap-4">
      <CardHeader>
        <CardTitle className="text-lg">Transferências entre armazéns</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <CommandError error={action.error} pending={action.pending} onRetry={action.retry} />
        <QueryState query={transfers} pending={transfers.isPending} empty={!rows?.length} emptyMessage="Ainda não há transferências.">
          <Table>
            <TableCaption className="sr-only">Transferências</TableCaption>
            <TableHeader>
              <TableRow>
                <TableHead>De</TableHead>
                <TableHead>Para</TableHead>
                <TableHead>Estado</TableHead>
                <TableHead>Criada em</TableHead>
                <TableHead>
                  <span className="sr-only">Ações</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows?.map((transfer) => (
                <TableRow key={transfer.id}>
                  <TableCell>{warehouse(transfer.fromWarehouseId)}</TableCell>
                  <TableCell>{warehouse(transfer.toWarehouseId)}</TableCell>
                  <TableCell>
                    <StateBadge status={transfer.status} states={transferStates} />
                  </TableCell>
                  <TableCell className="tabular-nums">{formatDateTime(transfer.createdAt, timeZone)}</TableCell>
                  <TableCell>
                    {canTransfer && (
                      <div className="flex justify-end">
                        {transfer.status === 'DRAFT' && (
                          <Button
                            size="sm"
                            aria-label={`Enviar transferência ${route(transfer)}`}
                            disabled={action.pending}
                            onClick={() => action.run({ id: transfer.id!, action: 'ship', label: route(transfer), transfer })}
                          >
                            <Truck aria-hidden="true" />
                            Enviar
                          </Button>
                        )}
                        {transfer.status === 'SHIPPED' && (
                          <Button
                            size="sm"
                            aria-label={`Receber transferência ${route(transfer)}`}
                            disabled={action.pending}
                            onClick={() => action.run({ id: transfer.id!, action: 'receive', label: route(transfer), transfer })}
                          >
                            <PackageCheck aria-hidden="true" />
                            Receber
                          </Button>
                        )}
                      </div>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <Pages page={page} total={transfers.data?.totalElements ?? 0} size={LIST_PAGE_SIZE} onPage={setPage} />
        </QueryState>
      </CardContent>
    </Card>
  );
}
