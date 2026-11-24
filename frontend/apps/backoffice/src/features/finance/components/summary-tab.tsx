import {
  Card,
  cn,
  StatBox,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  TONE_TINT,
} from '@restaurantpro/ui';
import { Banknote, Info, Landmark, Percent, Receipt } from 'lucide-react';
import { formatBusinessDate } from '../format';
import { useReceiptsByMethod, useSalesByDay } from '../hooks/use-reports';
import type { Period } from '../period';
import type { ReceiptsReport, SalesReport } from '../types';
import { QueryState } from './query-state';
import { Amount, SectionCard } from './section-card';

const REPORTS = 'os relatórios desta loja';

type Props = { storeId: string; period: Period };

/** "Resumo": the period's figures as the reports compute them (§5); nothing is summed here. */
export function SummaryTab({ storeId, period }: Props) {
  const sales = useSalesByDay(storeId, period);
  const receipts = useReceiptsByMethod(storeId, period);
  return (
    <div className="flex flex-col gap-6">
      <Kpis sales={sales.data} receipts={receipts.data} />
      <Provisional report={sales.data} />
      {/* Side by side from xl: at 1024 px each half is narrower than the sales table's four columns. */}
      <div className="grid gap-6 xl:grid-cols-2">
        <SectionCard title="Vendas por dia">
          <QueryState query={sales} empty={!sales.data?.rows?.length} emptyMessage="Sem vendas neste período." subject={REPORTS}>
            <SalesTable rows={sales.data?.rows ?? []} />
          </QueryState>
        </SectionCard>
        <SectionCard title="Recebimentos por meio de pagamento">
          <QueryState
            query={receipts}
            empty={!receipts.data?.rows?.length}
            emptyMessage="Sem recebimentos neste período."
            subject={REPORTS}
          >
            <ReceiptsTable rows={receipts.data?.rows ?? []} />
          </QueryState>
        </SectionCard>
      </div>
    </div>
  );
}

/**
 * The server's totals of the period, as the POS payment summary: tinted boxes in one white card, what was received in
 * the green of "pago", the rest on the muted surface.
 */
function Kpis({ sales, receipts }: { sales: SalesReport | undefined; receipts: ReceiptsReport | undefined }) {
  const totals = sales?.totals;
  return (
    <Card className="grid gap-3 p-4 sm:grid-cols-2 lg:grid-cols-4">
      <StatBox
        caption="Faturação bruta"
        icon={Receipt}
        tone="muted"
        value={<Amount value={totals?.gross} />}
        meta={totals && `${totals.documents ?? 0} documentos`}
      />
      <StatBox caption="Faturação líquida" icon={Landmark} tone="muted" value={<Amount value={totals?.net} />} />
      <StatBox caption="Impostos" icon={Percent} tone="muted" value={<Amount value={totals?.tax} />} />
      <StatBox caption="Recebido" icon={Banknote} tone="success" value={<Amount value={receipts?.totals?.received} />} />
    </Card>
  );
}

/** The reports are projections (D86): while facts wait to be projected, the figures are not final. */
function Provisional({ report }: { report: SalesReport | undefined }) {
  const pending = report?.final === false ? (report.pendingFacts ?? 0) : 0;
  if (pending <= 0) return null;
  return (
    // Not final yet: the amber of "a confirmar", as the POS footer's chips.
    <p className={cn('flex w-fit items-center gap-2 rounded-xl px-3 py-2 text-sm', TONE_TINT.warning)}>
      <Info aria-hidden="true" className="size-4" />
      Valores provisórios: {pending} movimentos ainda por processar.
    </p>
  );
}

function SalesTable({ rows }: { rows: NonNullable<SalesReport['rows']> }) {
  return (
    <Table>
      <TableCaption className="sr-only">Vendas por dia</TableCaption>
      <TableHeader>
        <TableRow>
          <TableHead>Dia</TableHead>
          <TableHead className="text-right">Documentos</TableHead>
          <TableHead className="text-right">Líquido</TableHead>
          <TableHead className="text-right">Bruto</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((row) => (
          <TableRow key={row.key}>
            <TableCell>{formatBusinessDate(row.key)}</TableCell>
            <TableCell className="text-right tabular-nums">{row.documents ?? 0}</TableCell>
            <TableCell className="text-right">
              <Amount value={row.net} />
            </TableCell>
            <TableCell className="text-right font-medium">
              <Amount value={row.gross} />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

function ReceiptsTable({ rows }: { rows: NonNullable<ReceiptsReport['rows']> }) {
  return (
    <Table>
      <TableCaption className="sr-only">Recebimentos por meio de pagamento</TableCaption>
      <TableHeader>
        <TableRow>
          <TableHead>Meio</TableHead>
          <TableHead className="text-right">A crédito</TableHead>
          <TableHead className="text-right">Recebido</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((row) => (
          <TableRow key={row.key ?? 'none'}>
            <TableCell className="font-mono">{row.label ?? row.key ?? '—'}</TableCell>
            <TableCell className="text-right">
              <Amount value={row.onAccount} />
            </TableCell>
            <TableCell className="text-right font-medium">
              <Amount value={row.received} />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
