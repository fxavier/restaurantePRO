import { StatusBadge, Table, TableBody, TableCaption, TableCell, TableHead, TableHeader, TableRow } from '@restaurantpro/ui';
import { storeTime } from '../format';
import { roundStatuses } from '../labels';
import type { BatchView } from '../types';
import { DetailSection } from './detail-section';

/** The rounds confirmed with "Pedir": when, whether production has them, and how many lines each carried. */
export function OrderRounds({ batches, timeZone }: { batches: BatchView[]; timeZone: string | undefined }) {
  return (
    <DetailSection title="Rondas" empty={batches.length ? undefined : 'Nenhuma ronda confirmada.'}>
      <Table>
        <TableCaption className="sr-only">Rondas</TableCaption>
        <TableHeader>
          <TableRow>
            <TableHead>Ronda</TableHead>
            <TableHead>Estado</TableHead>
            <TableHead>Confirmada</TableHead>
            <TableHead className="text-right">Linhas</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {batches.map((batch) => (
            <TableRow key={batch.id}>
              <TableCell className="font-medium">Ronda {batch.batchNo}</TableCell>
              <TableCell>
                <StatusBadge status={batch.status} statuses={roundStatuses} />
              </TableCell>
              <TableCell className="tabular-nums">{storeTime(batch.confirmedAt, timeZone)}</TableCell>
              <TableCell className="text-right tabular-nums">{batch.lines.length}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </DetailSection>
  );
}
