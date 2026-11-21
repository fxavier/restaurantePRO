import {
  Card,
  CardContent,
  CardDescription,
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
import { formatQuantity } from '../format';
import { useBelowMinimum, useValuation } from '../hooks/use-inventory-reads';
import { Amount } from './amount';
import { QueryState } from './query-state';

/** "Relatórios" (§5 inventory reports): the store's valuation and what is below its minimum, as the server computes them. */
export function ReportsTab({ storeId, currency }: { storeId: string; currency: string | undefined }) {
  const valuation = useValuation(storeId);
  const low = useBelowMinimum(storeId);
  const valuationRows = valuation.data?.rows;
  const lowRows = low.data?.rows;

  return (
    <div className="grid gap-6 xl:grid-cols-2">
      <Card className="gap-4">
        <CardHeader>
          <CardTitle className="text-lg">Valorização do stock</CardTitle>
          <CardDescription>
            Total: <Amount value={valuation.data?.totalValue} currency={currency} />
          </CardDescription>
        </CardHeader>
        <CardContent>
          <QueryState query={valuation} pending={valuation.isPending} empty={!valuationRows?.length} emptyMessage="Sem stock valorizado.">
            <Table>
              <TableCaption className="sr-only">Valorização do stock</TableCaption>
              <TableHeader>
                <TableRow>
                  <TableHead>Armazém</TableHead>
                  <TableHead>Artigo</TableHead>
                  <TableHead className="text-right">Quantidade</TableHead>
                  <TableHead className="text-right">Valor</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {valuationRows?.map((row) => (
                  <TableRow key={`${row.warehouseId}-${row.stockItemId}`}>
                    <TableCell className="font-mono">{row.warehouseCode}</TableCell>
                    <TableCell>{row.name}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatQuantity(row.quantity, row.baseUnit)}</TableCell>
                    <TableCell className="text-right">
                      <Amount value={row.value} currency={currency} />
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
          <CardTitle className="text-lg">Abaixo do mínimo</CardTitle>
          <CardDescription>O que falta para chegar ao mínimo de cada armazém.</CardDescription>
        </CardHeader>
        <CardContent>
          <QueryState query={low} pending={low.isPending} empty={!lowRows?.length} emptyMessage="Nenhum artigo abaixo do mínimo.">
            <Table>
              <TableCaption className="sr-only">Artigos abaixo do mínimo</TableCaption>
              <TableHeader>
                <TableRow>
                  <TableHead>Armazém</TableHead>
                  <TableHead>Artigo</TableHead>
                  <TableHead className="text-right">Quantidade</TableHead>
                  <TableHead className="text-right">Mínimo</TableHead>
                  <TableHead className="text-right">Em falta</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {lowRows?.map((row) => (
                  <TableRow key={`${row.warehouseId}-${row.stockItemId}`}>
                    <TableCell className="font-mono">{row.warehouseCode}</TableCell>
                    <TableCell>{row.name}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatQuantity(row.quantity, row.baseUnit)}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatQuantity(row.minQuantity, row.baseUnit)}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatQuantity(row.missing, row.baseUnit)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </QueryState>
        </CardContent>
      </Card>
    </div>
  );
}
