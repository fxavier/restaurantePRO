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
import { Ban, Lock, PackageOpen, Plus, Send } from 'lucide-react';
import { useState } from 'react';
import { formatDate } from '../format';
import { usePurchaseOrderAction } from '../hooks/use-inventory-commands';
import { LIST_PAGE_SIZE, usePurchaseOrders } from '../hooks/use-inventory-reads';
import { usePermission } from '../hooks/use-permission';
import { useRowCommand } from '../hooks/use-row-command';
import type { PurchaseOrderView } from '../types';
import { CommandError } from './command-error';
import { ConfirmDialog, FormDialog, type Confirmation } from './form-dialog';
import { PurchaseOrderForm } from './purchase-order-form';
import { Pages, QueryState } from './query-state';
import { ScrollTable } from './scroll-table';
import { ReceiveOrderForm } from './receive-order-form';
import { orderStates, progressLabel, StateBadge } from './state-badge';

/** "Ordens de compra" (§5 purchasing): the store's orders, create, submit, receive, close or cancel. */
export function PurchaseOrdersCard({ storeId, supplierName }: { storeId: string; supplierName: (id?: string) => string }) {
  const [page, setPage] = useState(0);
  const orders = usePurchaseOrders(storeId, page);
  const action = useRowCommand(usePurchaseOrderAction());
  const canManage = usePermission('PURCHASE_MANAGE', storeId);
  const canReceive = usePermission('GOODS_RECEIVE', storeId);
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null);
  const rows = orders.data?.rows;

  const confirmClose = (order: PurchaseOrderView) =>
    setConfirmation({
      title: `Fechar a encomenda ${order.number}?`,
      description: 'Uma encomenda fechada não recebe mais mercadoria; o que falta entregar deixa de ser esperado.',
      action: 'Fechar encomenda',
      onConfirm: () => action.run({ id: order.id!, action: 'close', label: order.number! }),
    });
  const confirmCancel = (order: PurchaseOrderView) =>
    setConfirmation({
      title: `Cancelar a encomenda ${order.number}?`,
      description: 'A encomenda deixa de valer e não pode ser reaberta.',
      action: 'Cancelar encomenda',
      onConfirm: () => action.run({ id: order.id!, action: 'cancel', label: order.number! }),
    });

  return (
    <Card className="gap-4">
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle className="text-lg">Ordens de compra</CardTitle>
        {canManage && (
          <FormDialog
            trigger={
              <>
                <Plus aria-hidden="true" />
                Nova encomenda
              </>
            }
            title="Nova encomenda"
            description="Uma encomenda a um fornecedor, entregue num armazém desta loja. Fica em rascunho até ser submetida."
          >
            {(close) => <PurchaseOrderForm storeId={storeId} onDone={close} />}
          </FormDialog>
        )}
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <CommandError error={action.error} pending={action.pending} onRetry={action.retry} />
        <QueryState query={orders} pending={orders.isPending} empty={!rows?.length} emptyMessage="Ainda não há encomendas.">
          <ScrollTable label="Encomendas a fornecedores">
            <TableHeader>
              <TableRow>
                <TableHead>Número</TableHead>
                <TableHead>Fornecedor</TableHead>
                <TableHead>Entrega prevista</TableHead>
                <TableHead>Estado</TableHead>
                <TableHead>
                  <span className="sr-only">Ações</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows?.map((order) => {
                const progress = progressLabel(order.progress);
                return (
                  <TableRow key={order.id}>
                    <TableCell className="font-mono font-medium">{order.number}</TableCell>
                    <TableCell>{supplierName(order.supplierId)}</TableCell>
                    <TableCell className="tabular-nums">{formatDate(order.expectedAt)}</TableCell>
                    <TableCell>
                      <StateBadge status={order.status} states={orderStates} />
                      {progress && <span className="text-sm text-muted-foreground"> · {progress}</span>}
                    </TableCell>
                    <TableCell>
                      <OrderActions
                        order={order}
                        may={{ manage: canManage, receive: canReceive }}
                        pending={action.pending}
                        onSubmit={() => action.run({ id: order.id!, action: 'submit', label: order.number! })}
                        onCancel={() => confirmCancel(order)}
                        onClose={() => confirmClose(order)}
                      />
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </ScrollTable>
          <Pages page={page} total={orders.data?.totalElements ?? 0} size={LIST_PAGE_SIZE} onPage={setPage} />
        </QueryState>
      </CardContent>
      <ConfirmDialog confirmation={confirmation} onClose={() => setConfirmation(null)} />
    </Card>
  );
}

type OrderActionsProps = {
  order: PurchaseOrderView;
  may: { manage: boolean; receive: boolean };
  pending: boolean;
  onSubmit: () => void;
  onCancel: () => void;
  onClose: () => void;
};

/** What an order's state allows (§8.3): a draft is submitted or cancelled; a submitted one received or closed. */
function OrderActions({ order, may, pending, onSubmit, onCancel, onClose }: OrderActionsProps) {
  const draft = order.status === 'DRAFT';
  const submitted = order.status === 'SUBMITTED';
  return (
    <div className="flex flex-wrap justify-end gap-2">
      {may.manage && draft && (
        <>
          <Button size="sm" aria-label={`Submeter ${order.number}`} disabled={pending} onClick={onSubmit}>
            <Send aria-hidden="true" />
            Submeter
          </Button>
          <Button size="sm" variant="outline" aria-label={`Cancelar ${order.number}`} disabled={pending} onClick={onCancel}>
            <Ban aria-hidden="true" />
            Cancelar
          </Button>
        </>
      )}
      {may.receive && submitted && (
        <FormDialog
          trigger={
            <>
              <PackageOpen aria-hidden="true" />
              Receber
            </>
          }
          triggerLabel={`Receber ${order.number}`}
          triggerSize="sm"
          title={`Receber a encomenda ${order.number}`}
          description="O que chegou, por artigo, na unidade base. A receção fica em rascunho até ser confirmada."
        >
          {(close) => <ReceiveOrderForm order={order} onDone={close} />}
        </FormDialog>
      )}
      {may.manage && submitted && (
        <Button size="sm" variant="outline" aria-label={`Fechar ${order.number}`} disabled={pending} onClick={onClose}>
          <Lock aria-hidden="true" />
          Fechar
        </Button>
      )}
    </div>
  );
}
