import {
  Money,
  StatusBadge,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  type MoneyValue,
} from '@restaurantpro/ui';
import { lineName } from '../format';
import { lineStatuses } from '../labels';
import type { BatchView, LineView } from '../types';
import { DetailSection } from './detail-section';
import { ScrollTable } from './scroll-table';

/** Every line of the order, drafts too, with its round and the amounts the server priced it at. */
export function OrderLines({ lines, batches }: { lines: LineView[]; batches: BatchView[] }) {
  const roundOf = (batchId: string | null) => batches.find((batch) => batch.id === batchId)?.batchNo;
  return (
    <DetailSection title="Linhas" empty={lines.length ? undefined : 'O pedido não tem linhas.'}>
      <ScrollTable label="Linhas">
        <TableHeader>
          <TableRow>
            <TableHead>Artigo</TableHead>
            <TableHead className="text-right">Qtd</TableHead>
            <TableHead className="text-right">Preço</TableHead>
            <TableHead>Desconto</TableHead>
            <TableHead className="text-right">Total</TableHead>
            <TableHead>Ronda</TableHead>
            <TableHead>Estado</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {lines.map((line) => {
            const round = roundOf(line.batchId);
            return (
              <TableRow key={line.id}>
                <TableCell className="whitespace-normal">
                  <span className="block font-medium">{lineName(line)}</span>
                  {line.itemCode && <span className="block font-mono text-xs text-muted-foreground">{line.itemCode}</span>}
                </TableCell>
                <TableCell className="text-right tabular-nums">{line.quantity}</TableCell>
                <TableCell className="text-right">
                  <Amount value={line.unitPrice} />
                </TableCell>
                <TableCell>
                  {line.discount ? (
                    <>
                      {line.discountLabel && `${line.discountLabel} `}
                      <Money value={line.discount} />
                    </>
                  ) : (
                    '—'
                  )}
                </TableCell>
                <TableCell className="text-right font-semibold">
                  <Amount value={line.gross} />
                </TableCell>
                <TableCell>{round === undefined ? '—' : `Ronda ${round}`}</TableCell>
                <TableCell>
                  <StatusBadge status={line.status} statuses={lineStatuses} />
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </ScrollTable>
    </DetailSection>
  );
}

function Amount({ value }: { value: MoneyValue | null }) {
  return value ? <Money value={value} /> : '—';
}
