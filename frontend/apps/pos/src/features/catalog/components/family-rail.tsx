import type { PointerEvent } from 'react';
import type { CatalogFamily } from '../catalog';

type Props = {
  families: CatalogFamily[];
  /** The family shown; none while a search is shown instead. */
  selected: string | undefined;
  onSelect: (familyId: string) => void;
};

/** Keeps the focus in the search field, so the barcode scanner still types there after a tap. */
const keepFocus = (event: PointerEvent) => event.preventDefault();

/** The order screen's left rail (DS: 160 px): one 56 px button per family, the shown one pressed. */
export function FamilyRail({ families, selected, onSelect }: Props) {
  return (
    <nav aria-label="Famílias" className="flex min-h-0 flex-col overflow-y-auto border-r bg-background p-2">
      <ul className="flex flex-col gap-2">
        {families.map((family) => (
          <li key={family.id}>
            <button
              type="button"
              aria-pressed={family.id === selected}
              onPointerDown={keepFocus}
              onClick={() => onSelect(family.id)}
              className="flex min-h-14 w-full items-center rounded-md px-3 text-left text-base font-medium hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none aria-pressed:bg-primary aria-pressed:text-primary-foreground"
            >
              {family.name}
            </button>
          </li>
        ))}
      </ul>
    </nav>
  );
}
