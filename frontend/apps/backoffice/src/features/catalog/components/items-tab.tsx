import {
  Card,
  CardContent,
  DataState,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { useState } from 'react';
import { availabilityLabel, destinationOf, taxLabel } from '../catalog-labels';
import { useFamilies, useItems, useTaxCategories } from '../hooks/use-catalog-queries';

/** "Artigos": the company's items with their family, destination, tax category and state. */
export function ItemsTab() {
  const items = useItems();
  const families = useFamilies();
  const taxCategories = useTaxCategories();
  const [now] = useState(() => new Date().toISOString());

  const familyName = (id: string | undefined) => families.data?.find((family) => family.id === id)?.name ?? '—';
  const taxOf = (id: string | undefined) => taxCategories.data?.find((category) => category.id === id);
  const list = items.data?.content ?? [];

  return (
    <Card>
      <CardContent>
        <DataState state={items.isPending ? 'loading' : list.length ? 'ready' : 'empty'} emptyMessage="Ainda não há artigos.">
          <Table>
            <TableCaption className="sr-only">Artigos do catálogo</TableCaption>
            <TableHeader>
              <TableRow>
                <TableHead>Artigo</TableHead>
                <TableHead>Família</TableHead>
                <TableHead>Destino de produção</TableHead>
                <TableHead>IVA</TableHead>
                <TableHead>Estado</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.map((item) => {
                const destination = destinationOf(item.productionDestination);
                return (
                  <TableRow key={item.id}>
                    <TableCell>
                      <span className="block font-medium">{item.name}</span>
                      <span className="font-mono text-xs text-muted-foreground">{item.code}</span>
                    </TableCell>
                    <TableCell>{familyName(item.familyId)}</TableCell>
                    <TableCell>
                      <span className={`rounded-sm px-2 py-0.5 text-xs font-semibold text-background ${destination.fill}`}>
                        {destination.label}
                      </span>
                    </TableCell>
                    <TableCell>{taxLabel(taxOf(item.taxCategoryId), now)}</TableCell>
                    <TableCell>{availabilityLabel(item.availability)}</TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </DataState>
      </CardContent>
    </Card>
  );
}
