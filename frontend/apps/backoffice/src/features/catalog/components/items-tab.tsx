import {
  Card,
  CardContent,
  DataState,
  Input,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { Search } from 'lucide-react';
import { useState } from 'react';
import { availabilityLabel, destinationOf, taxLabel } from '../catalog-labels';
import { useFamilies, useItems, useTaxCategories } from '../hooks/use-catalog-queries';
import { countByFamily, filterItems } from '../item-filter';
import type { FamilySummary, ItemSummary, TaxCategorySummary } from '../types';
import { CutListNotice } from './cut-list-notice';
import { FamilyPills } from './family-pills';
import { ItemFormDialog } from './item-form-dialog';
import { ItemSheet } from './item-sheet';
import { LoadError } from './load-error';

/** "Artigos": the company's items, narrowed by family and search, with their destination, tax category and state. */
export function ItemsTab() {
  const items = useItems();
  const families = useFamilies();
  const taxCategories = useTaxCategories();
  const [search, setSearch] = useState('');
  const [familyId, setFamilyId] = useState<string | undefined>();

  if (items.isError) {
    return <LoadError error={items.error} onRetry={() => void items.refetch()} forbidden="Sem permissão para ver o catálogo." />;
  }

  const all = items.data?.content ?? [];
  const shown = filterItems(all, { search, familyId });

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardContent className="flex flex-col gap-4">
          <div className="flex flex-wrap items-center gap-3">
            <SearchBox value={search} onChange={setSearch} />
            {items.data && <ItemFormDialog />}
          </div>
          <CutListNotice list={items.data} what="artigos" />
          <FamilyPills
            families={families.data?.content ?? []}
            counts={countByFamily(all)}
            total={all.length}
            selected={familyId}
            onSelect={setFamilyId}
          />
        </CardContent>
      </Card>
      <Card>
        <CardContent>
          <DataState state={items.isPending ? 'loading' : all.length ? 'ready' : 'empty'} emptyMessage="Ainda não há artigos.">
            <ItemsTable items={shown} families={families.data?.content} taxCategories={taxCategories.data?.content} />
          </DataState>
        </CardContent>
      </Card>
    </div>
  );
}

function SearchBox({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  return (
    <div className="relative min-w-64 flex-1">
      <Search aria-hidden="true" className="absolute top-2.5 left-3 size-4 text-muted-foreground" />
      <Input
        type="search"
        aria-label="Pesquisar artigos"
        placeholder="Pesquisar por nome ou código"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="pl-9"
      />
    </div>
  );
}

/** The items the search and family leave, or why there are none. */
type ItemsTableProps = { items: ItemSummary[]; families: FamilySummary[] | undefined; taxCategories: TaxCategorySummary[] | undefined };

function ItemsTable({ items, families, taxCategories }: ItemsTableProps) {
  const [now] = useState(() => new Date().toISOString());
  const familyName = (id: string | undefined) => families?.find((family) => family.id === id)?.name ?? '—';
  const taxOf = (id: string | undefined) => taxCategories?.find((category) => category.id === id);
  if (!items.length) return <p className="py-6 text-center text-muted-foreground">Nenhum artigo corresponde à pesquisa.</p>;
  return (
    <Table>
      <TableCaption className="sr-only">Artigos do catálogo</TableCaption>
      <TableHeader>
        <TableRow>
          <TableHead>Artigo</TableHead>
          <TableHead>Família</TableHead>
          <TableHead>Destino de produção</TableHead>
          <TableHead>IVA</TableHead>
          <TableHead>Estado</TableHead>
          <TableHead>
            <span className="sr-only">Ações</span>
          </TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {items.map((item) => (
          <TableRow key={item.id}>
            <TableCell>
              <span className="block font-medium">{item.name}</span>
              <span className="font-mono text-xs text-muted-foreground">{item.code}</span>
            </TableCell>
            <TableCell>{familyName(item.familyId)}</TableCell>
            <TableCell>
              <DestinationChip code={item.productionDestination} />
            </TableCell>
            <TableCell>{taxLabel(taxOf(item.taxCategoryId), now)}</TableCell>
            <TableCell>{availabilityLabel(item.availability)}</TableCell>
            <TableCell>
              <div className="flex justify-end gap-2">
                <ItemSheet item={item} />
                <ItemFormDialog item={item} />
              </div>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

/** A route hue as a border and a tint, the text in `foreground` (DESIGN.md "Light reference"). */
function DestinationChip({ code }: { code: string | undefined }) {
  const destination = destinationOf(code);
  return (
    <span className={`rounded-sm border px-2 py-0.5 text-xs font-semibold ${destination.fill}`}>{destination.label}</span>
  );
}
