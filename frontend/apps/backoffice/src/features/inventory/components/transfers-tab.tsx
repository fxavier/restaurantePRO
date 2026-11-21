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
import { formatDateTime, formatQuantity } from '../format';
import { useTransferAction } from '../hooks/use-inventory-commands';
import { LIST_PAGE_SIZE, useItemLookup, useNames, useTransfers } from '../hooks/use-inventory-reads';
import { usePermission } from '../hooks/use-permission';
import { useRowCommand } from '../hooks/use-row-command';
import type { TransferView } from '../types';
import { CommandError } from './command-error';
import { ConfirmDialog, type Confirmation } from './form-dialog';
import { Pages, QueryState } from './query-state';
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
 * one as shipped. Creating a transfer and a partial receipt are follow-ups.
 */
export function TransfersTab({ storeId, timeZone }: { storeId: string; timeZone: string | undefined }) {
  const [page, setPage] = useState(0);
  const transfers = useTransfers(storeId, page);
  const names = useNames(storeId);
  const action = useRowCommand(useTransferAction());
  const canTransfer = usePermission('INVENTORY_TRANSFER', storeId);
  const { item } = useItemLookup();
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null);
  const rows = transfers.data?.rows;
  const warehouse = (id?: string) => names.warehouseName(id, 'Outra loja');
  const route = (transfer: TransferView) => `${warehouse(transfer.fromWarehouseId)} → ${warehouse(transfer.toWarehouseId)}`;
  const shipped = (transfer: TransferView) =>
    (transfer.lines ?? [])
      .map((line) => {
        const stockItem = item(line.stockItemId);
        return `${formatQuantity(line.quantityShipped, stockItem?.baseUnit)} de ${stockItem?.name ?? '—'}`;
      })
      .join(', ');
  const run = (transfer: TransferView, name: Step['action']) =>
    action.run({ id: transfer.id!, action: name, label: route(transfer), transfer });
  /** Receiving credits the destination with what was shipped and cannot be undone: the user reads it first. */
  const confirmReceive = (transfer: TransferView) =>
    setConfirmation({
      title: `Receber a transferência ${route(transfer)}?`,
      description: `Fica registado como recebido o que foi enviado: ${shipped(transfer)}. Não pode ser desfeito: uma diferença exige um ajuste de stock.`,
      action: 'Receber transferência',
      onConfirm: () => run(transfer, 'receive'),
    });

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
                        onRun={(name) => (name === 'receive' ? confirmReceive(transfer) : run(transfer, name))}
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
      <ConfirmDialog confirmation={confirmation} onClose={() => setConfirmation(null)} />
    </Card>
  );
}

type TransferActionProps = {
  transfer: TransferView;
  storeId: string;
  route: string;
  pending: boolean;
  onRun: (action: Step['action']) => void;
};

function TransferAction({ transfer, storeId, route, pending, onRun }: TransferActionProps) {
  const step = NEXT_STEP[transfer.status ?? ''];
  if (!step || transfer[step.side] !== storeId) return null;
  return (
    <div className="flex justify-end">
      <Button size="sm" aria-label={`${step.label} transferência ${route}`} disabled={pending} onClick={() => onRun(step.action)}>
        <step.icon aria-hidden="true" />
        {step.label}
      </Button>
    </div>
  );
}
