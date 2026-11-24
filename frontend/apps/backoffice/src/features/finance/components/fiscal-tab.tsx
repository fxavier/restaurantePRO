import {
  Button,
  StatusBadge,
  StatusPill,
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
import { formatInstant } from '../format';
import { useSensitiveCommand } from '../hooks/use-sensitive-command';
import { PAGE_SIZE, useDocuments, useSeries } from '../hooks/use-reports';
import type { Period } from '../period';
import type { DocumentSummary, SeriesView } from '../types';
import { CommandAlert } from './command-alert';
import { ConfirmDialog } from './confirm-dialog';
import { Pager } from './pager';
import { QueryState } from './query-state';
import { Amount, SectionCard } from './section-card';
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

/**
 * "Fiscal" (§5): the store's document series (managed with a step-up) and the documents issued in the period. The
 * documents start again at their first page when `periodKey` changes; the series, which have no period, stay as they are.
 */
export function FiscalTab({ storeId, period, periodKey }: { storeId: string; period: Period; periodKey: string }) {
  return (
    <div className="flex flex-col gap-6">
      <SeriesSection storeId={storeId} />
      <DocumentsSection key={periodKey} storeId={storeId} period={period} />
    </div>
  );
}

function SeriesSection({ storeId }: { storeId: string }) {
  const series = useSeries(storeId);
  const deactivate = useSensitiveCommand(storeId);
  const [toDeactivate, setToDeactivate] = useState<SeriesView | null>(null);
  return (
    <SectionCard
      title="Séries de documentos"
      description="A numeração dos documentos de venda da loja."
      action={series.data && <SeriesDialog storeId={storeId} />}
    >
      {deactivate.failure && <CommandAlert {...deactivate.failure} />}
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
              <SeriesRow key={one.id} series={one} pending={deactivate.pending} onDeactivate={() => setToDeactivate(one)} />
            ))}
          </TableBody>
        </Table>
      </QueryState>
      <ConfirmDialog
        target={toDeactivate}
        title={(one) => `Desativar a série ${one?.code ?? ''}?`}
        description="A loja deixa de emitir documentos nesta série. Para continuar a vender, crie e ative outra."
        action="Desativar"
        destructive
        onCancel={() => setToDeactivate(null)}
        onConfirm={(one) => deactivate.send({ type: 'deactivateSeries', seriesId: one.id!, label: one.code ?? '' })}
      />
      {deactivate.dialog}
    </SectionCard>
  );
}

function SeriesRow({ series, pending, onDeactivate }: { series: SeriesView; pending: boolean; onDeactivate: () => void }) {
  const type = series.documentType ?? '';
  return (
    <TableRow>
      <TableCell className="font-mono font-medium">{series.code}</TableCell>
      <TableCell>{TYPES[type] ?? type}</TableCell>
      <TableCell className="text-right tabular-nums">{series.nextNumber}</TableCell>
      <TableCell>
        <StatusBadge look="tint" status={series.active ? 'true' : 'false'} statuses={ACTIVE} />
      </TableCell>
      <TableCell>
        {series.active && (
          <div className="flex justify-end">
            <Button size="sm" variant="outline" aria-label={`Desativar a série ${series.code}`} disabled={pending} onClick={onDeactivate}>
              <Ban aria-hidden="true" />
              Desativar
            </Button>
          </div>
        )}
      </TableCell>
    </TableRow>
  );
}

function DocumentsSection({ storeId, period }: { storeId: string; period: Period }) {
  const [page, setPage] = useState(0);
  const documents = useDocuments(storeId, period, page);
  const docs = documents.data?.rows ?? [];
  return (
    <SectionCard title="Documentos fiscais" description="Os documentos emitidos no período, os mais recentes primeiro.">
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
              <DocumentRow key={document.id} document={document} timeZone={period.timeZone} />
            ))}
          </TableBody>
        </Table>
        <Pager page={page} totalElements={documents.data?.totalElements ?? 0} pageSize={PAGE_SIZE} onPage={setPage} />
      </QueryState>
    </SectionCard>
  );
}

function DocumentRow({ document, timeZone }: { document: DocumentSummary; timeZone: string }) {
  const status = document.status ?? '';
  return (
    <TableRow>
      <TableCell className="font-mono font-medium">
        {document.seriesCode}/{document.number}
      </TableCell>
      <TableCell className="whitespace-nowrap tabular-nums">{formatInstant(document.issuedAt, timeZone)}</TableCell>
      <TableCell>{document.buyerName}</TableCell>
      <TableCell>
        {status in DOCUMENT ? (
          <StatusBadge look="tint" status={status as keyof typeof DOCUMENT} statuses={DOCUMENT} />
        ) : (
          // A state this screen does not know yet: the server's name, in the neutral pill (never inferred, §8.3).
          status && <StatusPill tone="neutral">{status}</StatusPill>
        )}
      </TableCell>
      <TableCell className="text-right font-medium">
        <Amount value={document.totalGross} />
      </TableCell>
    </TableRow>
  );
}
