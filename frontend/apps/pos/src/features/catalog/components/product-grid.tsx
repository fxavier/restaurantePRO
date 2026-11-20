import { formatMoney } from '@restaurantpro/ui';
import { CirclePlus, ListPlus } from 'lucide-react';
import type { PointerEvent } from 'react';
import { type CatalogItem, needsChoices } from '../catalog';
import { DestinationBadge } from './destination-badge';

type Props = { items: CatalogItem[]; disabled: boolean; onPick: (item: CatalogItem) => void };

/** Keeps the focus in the search field, so the barcode scanner still types there after a tap. */
const keepFocus = (event: PointerEvent) => event.preventDefault();

/**
 * The products as fixed-size tiles (DS: ≥ 112 × 80 px; Stitch's product card): where it is prepared and its code on
 * top, the name, and the catalog price; an item that asks for choices first carries the options icon, any other the add
 * icon. The name comes first in the DOM, so it starts the tile's accessible name; the top row is placed by `order-first`.
 * Disabled while a line is being added or the POS has no connection.
 */
export function ProductGrid({ items, disabled, onPick }: Props) {
  if (items.length === 0) return <p className="py-6 text-center text-muted-foreground">Nenhum artigo encontrado.</p>;
  return (
    <ul aria-label="Artigos" className="grid grid-cols-[repeat(auto-fill,minmax(9rem,1fr))] gap-3">
      {items.map((item) => (
        <li key={item.id}>
          <button
            type="button"
            disabled={disabled}
            onPointerDown={keepFocus}
            onClick={() => onPick(item)}
            className="flex h-32 w-full flex-col gap-1 rounded-xl border bg-card p-3 text-left hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background focus-visible:outline-none active:border-primary active:bg-background disabled:opacity-50"
          >
            <span className="line-clamp-2 text-base leading-tight font-semibold">{item.name}</span>{' '}
            <span className="order-first flex min-h-5 items-center justify-between gap-2">
              <DestinationBadge code={item.productionDestination} />{' '}
              <span className="ml-auto font-mono text-xs text-muted-foreground">#{item.code}</span>
            </span>{' '}
            <span className="mt-auto flex items-center justify-between gap-1 text-base font-bold xl:text-lg text-primary tabular-nums">
              {formatMoney(item.price)}
              {needsChoices(item) ? (
                <ListPlus aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
              ) : (
                <CirclePlus aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
              )}
            </span>
          </button>
        </li>
      ))}
    </ul>
  );
}
