import { formatMoney } from '@restaurantpro/ui';
import { ListPlus } from 'lucide-react';
import type { PointerEvent } from 'react';
import { type CatalogItem, needsChoices } from '../catalog';

type Props = { items: CatalogItem[]; disabled: boolean; onPick: (item: CatalogItem) => void };

/** Keeps the focus in the search field, so the barcode scanner still types there after a tap. */
const keepFocus = (event: PointerEvent) => event.preventDefault();

/**
 * The products as fixed-size tiles (DS: ≥ 112 × 80 px): name and catalog price; an item that asks for choices first
 * carries an icon. Disabled while a line is being added or the POS has no connection.
 */
export function ProductGrid({ items, disabled, onPick }: Props) {
  if (items.length === 0) return <p className="py-6 text-center text-muted-foreground">Nenhum artigo encontrado.</p>;
  return (
    <ul aria-label="Artigos" className="grid grid-cols-[repeat(auto-fill,minmax(7rem,1fr))] gap-2">
      {items.map((item) => (
        <li key={item.id}>
          <button
            type="button"
            disabled={disabled}
            onPointerDown={keepFocus}
            onClick={() => onPick(item)}
            className="flex h-24 w-full flex-col justify-between rounded-lg border-2 border-input bg-background p-2 text-left focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:outline-none active:bg-muted disabled:opacity-50"
          >
            <span className="line-clamp-2 text-base leading-tight font-medium">{item.name}</span>{' '}
            <span className="flex items-center justify-between text-sm text-muted-foreground tabular-nums">
              {formatMoney(item.price)}
              {needsChoices(item) && <ListPlus aria-hidden="true" className="size-5" />}
            </span>
          </button>
        </li>
      ))}
    </ul>
  );
}
