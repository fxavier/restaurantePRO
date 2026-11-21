import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  Money,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { Banknote, Info, Landmark, Percent, Receipt } from 'lucide-react';
import { formatBusinessDate } from '../format';
import { useReceiptsByMethod, useSalesByDay } from '../hooks/use-reports';
import type { Period } from '../period';
import type { Money as MoneyValue } from '../types';
import { KpiCard } from './kpi-card';
import { QueryState } from './query-state';

const REPORTS = 'os relatórios desta loja';

/** "Resumo": the period's figures as the reports compute them (§5); nothing is summed here. */
export function SummaryTab({ storeId, period }: { storeId: string; period: Period }) {
  const sales = useSalesByDay(storeId, period);
  const receipts = useReceiptsByMethod(storeId, period);
  const totals = sales.data?.totals;
  const pending = sales.data?.final === false ? (sales.data.pendingFacts ?? 0) : 0;
  return (
    <div className="flex flex-col gap-6">
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <KpiCard
          label="Faturação bruta"
          icon={Receipt}
          value={amount(totals?.gross)}
          meta={totals ? `${totals.documents ?? 0} documentos` : undefined}
        />
        <KpiCard label="Faturação líquida" icon={Landmark} value={amount(totals?.net)} />
        <KpiCard label="Impostos" icon={Percent} value={amount(totals?.tax)} />
        <KpiCard label="Recebido" icon={Banknote} value={amount(receipts.data?.totals?.received)} />
      </div>
      {pending > 0 && (
        <p className="flex items-center gap-2 text-sm text-muted-foreground">
          <Info aria-hidden="true" className="size-4" />
          Valores provisórios: {pending} movimentos ainda por processar.
        </p>
      )}
      <div className="grid gap-6 lg:grid-cols-2">
        <Card className="gap-4">
          <CardHeader>
            <CardTitle className="text-lg">Vendas por dia</CardTitle>
          </CardHeader>
          <CardContent>
            <QueryState query={sales} empty={!sales.data?.rows?.length} emptyMessage="Sem vendas neste período." subject={REPORTS}>
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
                  {sales.data?.rows?.map((row) => (
                    <TableRow key={row.key}>
                      <TableCell>{formatBusinessDate(row.key)}</TableCell>
                      <TableCell className="text-right tabular-nums">{row.documents ?? 0}</TableCell>
                      <TableCell className="text-right">{amount(row.net)}</TableCell>
                      <TableCell className="text-right font-medium">{amount(row.gross)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </QueryState>
          </CardContent>
        </Card>
        <Card className="gap-4">
          <CardHeader>
            <CardTitle className="text-lg">Recebimentos por meio de pagamento</CardTitle>
          </CardHeader>
          <CardContent>
            <QueryState
              query={receipts}
              empty={!receipts.data?.rows?.length}
              emptyMessage="Sem recebimentos neste período."
              subject={REPORTS}
            >
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
                  {receipts.data?.rows?.map((row) => (
                    <TableRow key={row.key ?? 'none'}>
                      <TableCell className="font-mono">{row.label ?? row.key ?? '—'}</TableCell>
                      <TableCell className="text-right">{amount(row.onAccount)}</TableCell>
                      <TableCell className="text-right font-medium">{amount(row.received)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </QueryState>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function amount(value: MoneyValue | undefined) {
  return value ? <Money value={value} /> : '—';
}
