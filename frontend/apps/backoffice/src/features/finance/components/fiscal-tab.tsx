import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Money,
  StatusBadge,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { Ban, CheckCircle2, CircleDashed, FileCheck2, MinusCircle } from 'lucide-react';
import { useState } from 'react';
import { isStepUpRequired } from '../../../lib/step-up';
import type { FinanceCommand } from '../finance-commands';
import { formatInstant } from '../format';
import { useFinanceCommand } from '../hooks/use-finance-commands';
import { useFinanceStepUp } from '../hooks/use-finance-step-up';
import { PAGE_SIZE, useDocuments, useSeries } from '../hooks/use-reports';
import type { Period } from '../period';
import type { SeriesView } from '../types';
import { CommandAlert } from './command-alert';
import { Pager } from './pager';
import { QueryState } from './query-state';
import { SeriesDialog } from './series-dialog';

const ACTIVE = {
  true: { label: 'Ativa', icon: CheckCircle2, tone: 'success' as const },
  false: { label: 'Inativa', icon: MinusCircle, tone: 'neutral' as const },
};
const DOCUMENT = {
  ISSUED: { label: 'Emitido', icon: FileCheck2, tone: 'success' as const },
  DRAFT: { label: 'Rascunho', icon: CircleDashed, tone: 'warning' as const },
};
const TYPES: Record<string, string> = { SALE: 'Venda' };

/** "Fiscal" (§5): the store's document series (managed with a step-up) and the documents issued in the period. */
export function FiscalTab({ storeId, period }: { storeId: string; period: Period }) {
  const series = useSeries(storeId);
  const [page, setPage] = useState(0);
  const documents = useDocuments(storeId, period, page);
  const deactivate = useFinanceCommand();
  const stepUp = useFinanceStepUp(storeId);
  const [toDeactivate, setToDeactivate] = useState<SeriesView | null>(null);
  const send = (command: FinanceCommand) =>
    deactivate.mutate(command, { onError: (error) => isStepUpRequired(error) && stepUp.prompt(error, command) });
  const docs = documents.data?.rows ?? [];

  return (
    <div className="flex flex-col gap-6">
      <Card className="gap-4">
        <CardHeader className="flex flex-wrap items-start justify-between gap-3">
          <div className="flex flex-col gap-1.5">
            <CardTitle className="text-lg">Séries de documentos</CardTitle>
            <CardDescription>A numeração dos documentos de venda da loja.</CardDescription>
          </div>
          {series.data && <SeriesDialog storeId={storeId} />}
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {deactivate.error && !isStepUpRequired(deactivate.error) && (
            <CommandAlert
              error={deactivate.error}
              pending={deactivate.isPending}
              onRetry={() => deactivate.variables && send(deactivate.variables)}
            />
          )}
          <QueryState query={series} empty={!series.data?.length} emptyMessage="A loja ainda não tem séries." subject="as séries fiscais">
            <Table>
              <TableCaption className="sr-only">Séries de documentos</TableCaption>
              <TableHeader>
                <TableRow>
                  <TableHead>Código</TableHead>
                  <TableHead>Tipo</TableHead>
                  <TableHead className="text-right">Próximo número</TableHead>
                  <TableHead>Estado</TableHead>
                  <TableHead>
                    <span className="sr-only">Ações</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {series.data?.map((one) => (
                  <TableRow key={one.id}>
                    <TableCell className="font-mono font-medium">{one.code}</TableCell>
                    <TableCell>{TYPES[one.documentType ?? ''] ?? one.documentType}</TableCell>
                    <TableCell className="text-right tabular-nums">{one.nextNumber}</TableCell>
                    <TableCell>
                      <StatusBadge status={one.active ? 'true' : 'false'} statuses={ACTIVE} />
                    </TableCell>
                    <TableCell>
                      {one.active && (
                        <div className="flex justify-end">
                          <Button
                            size="sm"
                            variant="outline"
                            aria-label={`Desativar a série ${one.code}`}
                            disabled={deactivate.isPending}
                            onClick={() => setToDeactivate(one)}
                          >
                            <Ban aria-hidden="true" />
                            Desativar
                          </Button>
                        </div>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </QueryState>
        </CardContent>
      </Card>
      <Card className="gap-4">
        <CardHeader>
          <CardTitle className="text-lg">Documentos fiscais</CardTitle>
          <CardDescription>Os documentos emitidos no período, os mais recentes primeiro.</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <QueryState query={documents} empty={docs.length === 0} emptyMessage="Sem documentos neste período." subject="os documentos desta loja">
            <Table>
              <TableCaption className="sr-only">Documentos fiscais</TableCaption>
              <TableHeader>
                <TableRow>
                  <TableHead>Número</TableHead>
                  <TableHead>Emitido</TableHead>
                  <TableHead>Cliente</TableHead>
                  <TableHead>Estado</TableHead>
                  <TableHead className="text-right">Total</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {docs.map((document) => (
                  <TableRow key={document.id}>
                    <TableCell className="font-mono font-medium">
                      {document.seriesCode}/{document.number ?? '—'}
                    </TableCell>
                    <TableCell className="whitespace-nowrap tabular-nums">{formatInstant(document.issuedAt, period.timeZone)}</TableCell>
                    <TableCell>{document.buyerName ?? ''}</TableCell>
                    <TableCell>
                      {document.status && document.status in DOCUMENT ? (
                        <StatusBadge status={document.status as keyof typeof DOCUMENT} statuses={DOCUMENT} />
                      ) : (
                        document.status
                      )}
                    </TableCell>
                    <TableCell className="text-right font-medium">
                      {document.totalGross ? <Money value={document.totalGross} /> : '—'}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <Pager page={page} totalElements={documents.data?.totalElements ?? 0} pageSize={PAGE_SIZE} onPage={setPage} />
          </QueryState>
        </CardContent>
      </Card>
      <AlertDialog open={toDeactivate !== null} onOpenChange={(open) => !open && setToDeactivate(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Desativar a série {toDeactivate?.code}?</AlertDialogTitle>
            <AlertDialogDescription>
              A loja deixa de emitir documentos nesta série. Para continuar a vender, crie e ative outra.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancelar</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={() => send({ type: 'deactivateSeries', seriesId: toDeactivate!.id!, label: toDeactivate!.code ?? '' })}
            >
              Desativar
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      {stepUp.dialog}
    </div>
  );
}
