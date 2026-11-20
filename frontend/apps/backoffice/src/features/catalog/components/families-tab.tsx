import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  DataState,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { useFamilies } from '../hooks/use-catalog-queries';
import { FamilyFormDialog } from './family-form-dialog';
import { LoadError } from './load-error';

/** "Famílias": the families that group the items on the POS, each with its parent when it is a subfamily. */
export function FamiliesTab() {
  const families = useFamilies();
  if (families.isError) {
    return <LoadError error={families.error} onRetry={() => void families.refetch()} forbidden="Sem permissão para ver as famílias." />;
  }
  const list = families.data ?? [];
  const nameOf = (id: string | undefined) => (id ? (list.find((family) => family.id === id)?.name ?? '—') : '—');
  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle className="text-lg">Famílias</CardTitle>
        {families.data && <FamilyFormDialog families={list} />}
      </CardHeader>
      <CardContent>
        <DataState state={families.isPending ? 'loading' : list.length ? 'ready' : 'empty'} emptyMessage="Ainda não há famílias.">
          <Table>
            <TableCaption className="sr-only">Famílias do catálogo</TableCaption>
            <TableHeader>
              <TableRow>
                <TableHead>Nome</TableHead>
                <TableHead>Código</TableHead>
                <TableHead>Família superior</TableHead>
                <TableHead>
                  <span className="sr-only">Ações</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.map((family) => (
                <TableRow key={family.id}>
                  <TableCell className="font-medium">{family.name}</TableCell>
                  <TableCell className="font-mono text-muted-foreground">{family.code}</TableCell>
                  <TableCell>{nameOf(family.parentId)}</TableCell>
                  <TableCell className="text-right">
                    <FamilyFormDialog family={family} families={list} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </DataState>
      </CardContent>
    </Card>
  );
}
