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
import { PackageCheck } from 'lucide-react';
import { useState } from 'react';
import { formatDateTime } from '../format';
import { useReceiptAction } from '../hooks/use-inventory-commands';
import { LIST_PAGE_SIZE, useReceipts } from '../hooks/use-inventory-reads';
import { usePermission } from '../hooks/use-permission';
import { useRowCommand } from '../hooks/use-row-command';
import { CommandError } from './command-error';
import { Pages, QueryState } from './query-state';
import { documentStates, StateBadge } from './state-badge';

type ReceiptsCardProps = { storeId: string; timeZone: string | undefined; supplierName: (id?: string) => string };

/** "Receção de mercadoria" (§5 purchasing): the store's goods receipts; confirming one moves the stock (§8.4). */
export function ReceiptsCard({ storeId, timeZone, supplierName }: ReceiptsCardProps) {
  const [page, setPage] = useState(0);
  const receipts = useReceipts(storeId, page);
  const action = useRowCommand(useReceiptAction());
  const canReceive = usePermission('GOODS_RECEIVE', storeId);
  const rows = receipts.data?.rows;

  return (
    <Card className="gap-4">
      <CardHeader>
        <CardTitle className="text-lg">Receção de mercadoria</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <CommandError error={action.error} pending={action.pending} onRetry={action.retry} />
        <QueryState query={receipts} pending={receipts.isPending} empty={!rows?.length} emptyMessage="Ainda não há receções.">
          <Table>
            <TableCaption className="sr-only">Receções de mercadoria</TableCaption>
            <TableHeader>
              <TableRow>
                <TableHead>Guia</TableHead>
                <TableHead>Fornecedor</TableHead>
                <TableHead>Estado</TableHead>
                <TableHead>Recebida em</TableHead>
                <TableHead>
                  <span className="sr-only">Ações</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows?.map((receipt) => {
                const name = receipt.supplierDocumentRef ?? '—';
                return (
                  <TableRow key={receipt.id}>
                    <TableCell className="font-mono font-medium">{name}</TableCell>
                    <TableCell>{supplierName(receipt.supplierId)}</TableCell>
                    <TableCell>
                      <StateBadge status={receipt.status} states={documentStates} />
                    </TableCell>
                    <TableCell className="tabular-nums">{formatDateTime(receipt.receivedAt, timeZone)}</TableCell>
                    <TableCell>
                      {canReceive && receipt.status === 'DRAFT' && (
                        <div className="flex justify-end">
                          <Button
                            size="sm"
                            aria-label={`Confirmar receção ${name}`}
                            disabled={action.pending}
                            onClick={() => action.run({ id: receipt.id!, action: 'confirm', label: name })}
                          >
                            <PackageCheck aria-hidden="true" />
                            Confirmar
                          </Button>
                        </div>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
          <Pages page={page} total={receipts.data?.totalElements ?? 0} size={LIST_PAGE_SIZE} onPage={setPage} />
        </QueryState>
      </CardContent>
    </Card>
  );
}
