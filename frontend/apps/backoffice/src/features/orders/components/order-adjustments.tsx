import { Money, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@restaurantpro/ui';
import { lineName, storeTime } from '../format';
import { adjustmentKind } from '../labels';
import type { LineView } from '../types';
import { DetailSection } from './detail-section';
import { ScrollTable } from './scroll-table';

/** Every discount, offer or reward applied to a line, with its reason, in the order the server recorded them. */
export function OrderAdjustments({ lines, timeZone }: { lines: LineView[]; timeZone: string | undefined }) {
  const adjustments = lines.flatMap((line) => line.adjustments.map((adjustment) => ({ line, adjustment })));
  return (
    <DetailSection title="Descontos e ofertas" empty={adjustments.length ? undefined : 'Sem descontos nem ofertas.'}>
      <ScrollTable label="Descontos e ofertas">
        <TableHeader>
          <TableRow>
            <TableHead>Artigo</TableHead>
            <TableHead>Tipo</TableHead>
            <TableHead className="text-right">Qtd</TableHead>
            <TableHead>Preço</TableHead>
            <TableHead>Motivo</TableHead>
            <TableHead>Hora</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {adjustments.map(({ line, adjustment }) => (
            <TableRow key={adjustment.id}>
              <TableCell className="whitespace-normal">{lineName({ ...line, variantName: null })}</TableCell>
              <TableCell>
                {adjustmentKind(adjustment.kind)}
                {adjustment.percent && ` ${adjustment.percent}%`}
              </TableCell>
              <TableCell className="text-right tabular-nums">{adjustment.quantity}</TableCell>
              <TableCell>
                <Money value={adjustment.previousUnitPrice} /> → <Money value={adjustment.unitPrice} />
              </TableCell>
              <TableCell className="whitespace-normal">{adjustment.reason}</TableCell>
              <TableCell className="tabular-nums">{storeTime(adjustment.at, timeZone)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </ScrollTable>
    </DetailSection>
  );
}
