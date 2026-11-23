import {
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { PackageCheck, Truck, type LucideIcon } from 'lucide-react';
import { useState } from 'react';
import { formatDateTime } from '../format';
import { useTransferAction } from '../hooks/use-inventory-commands';
import { LIST_PAGE_SIZE, useNames, useTransfers } from '../hooks/use-inventory-reads';
import { usePermission } from '../hooks/use-permission';
import { useRowCommand } from '../hooks/use-row-command';
import type { TransferView } from '../types';
import { CommandError } from './command-error';
import { FormDialog } from './form-dialog';
import { Pages, QueryState } from './query-state';
import { ReceiveTransferForm } from './receive-transfer-form';
import { ScrollTable } from './scroll-table';
import { StateBadge, transferStates } from './state-badge';

type Step = { action: 'ship' | 'receive'; label: string; icon: LucideIcon; side: 'fromStoreId' | 'toStoreId' };

/**
 * The one command each live state offers (D120: DRAFT → SHIPPED → RECEIVED), in the store that runs it: the source
 * ships, the destination receives (TransferService checks INVENTORY_TRANSFER there).
 */
const NEXT_STEP: Record<string, Step | undefined> = {
  DRAFT: { action: 'ship', label: 'Enviar', icon: Truck, side: 'fromStoreId' },
  SHIPPED: { action: 'receive', label: 'Receber', icon: PackageCheck, side: 'toStoreId' },
};

/**
 * "Transferências" (§5 inventory, D120): the store's transfers between warehouses; ship a draft, receive a shipped
 * one with what arrived of each line and, when short, why (Q3). Creating a transfer is a follow-up.
 */
export function TransfersTab({ storeId, timeZone }: { storeId: string; timeZone: string | undefined }) {
  const [page, setPage] = useState(0);
  const transfers = useTransfers(storeId, page);
  const names = useNames(storeId);
  const action = useRowCommand(useTransferAction());
  const canTransfer = usePermission('INVENTORY_TRANSFER', storeId);
  const rows = transfers.data?.rows;
  const warehouse = (id?: string) => names.warehouseName(id, 'Outra loja');
  const route = (transfer: TransferView) => `${warehouse(transfer.fromWarehouseId)} → ${warehouse(transfer.toWarehouseId)}`;
  const ship = (transfer: TransferView) => action.run({ id: transfer.id!, action: 'ship', label: route(transfer) });

  return (
    <Card className="gap-4">
      <CardHeader>
        <CardTitle className="text-lg">Transferências entre armazéns</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <CommandError error={action.error} pending={action.pending} onRetry={action.retry} />
        <QueryState query={transfers} pending={transfers.isPending} empty={!rows?.length} emptyMessage="Ainda não há transferências.">
          <ScrollTable label="Transferências">
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
                      <TransferAction
                        transfer={transfer}
                        storeId={storeId}
                        route={route(transfer)}
                        pending={action.pending}
                        onShip={() => ship(transfer)}
                      />
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </ScrollTable>
          <Pages page={page} total={transfers.data?.totalElements ?? 0} size={LIST_PAGE_SIZE} onPage={setPage} />
        </QueryState>
      </CardContent>
    </Card>
  );
}

type TransferActionProps = {
  transfer: TransferView;
  storeId: string;
  route: string;
  pending: boolean;
  onShip: () => void;
};

/** Shipping is one tap; receiving opens what arrived, which cannot be undone once registered. */
function TransferAction({ transfer, storeId, route, pending, onShip }: TransferActionProps) {
  const step = NEXT_STEP[transfer.status ?? ''];
  if (!step || transfer[step.side] !== storeId) return null;
  const label = `${step.label} transferência ${route}`;
  return (
    <div className="flex justify-end">
      {step.action === 'ship' ? (
        <Button size="sm" aria-label={label} disabled={pending} onClick={onShip}>
          <step.icon aria-hidden="true" />
          {step.label}
        </Button>
      ) : (
        <FormDialog
          trigger={
            <>
              <step.icon aria-hidden="true" />
              {step.label}
            </>
          }
          triggerLabel={label}
          triggerSize="sm"
          title={`Receber a transferência ${route}`}
          description="O que chegou, por artigo, na unidade base. O que não chegou fica registado como perda da transferência, com o motivo. Não pode ser desfeito."
        >
          {(close) => <ReceiveTransferForm transfer={transfer} onDone={close} />}
        </FormDialog>
      )}
    </div>
  );
}
