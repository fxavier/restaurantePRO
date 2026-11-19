import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  formatMoney,
} from '@restaurantpro/ui';
import { type FormEvent, useState } from 'react';
import { type CatalogItem, itemLabel, type MenuGroup, type MenuOption, type PosCatalog } from '../../catalog/catalog';
import type { AddLineRequest } from '../hooks/use-order-commands';
import { quantityLabel } from '../order';
import { NoteField } from './note-field';

export type Choices = Pick<AddLineRequest, 'variantId' | 'notes' | 'components'>;

type Props = {
  catalog: PosCatalog;
  item: CatalogItem;
  /** A variant already chosen (a barcode carried it). */
  variantId?: string;
  onConfirm: (choices: Choices) => void;
  onCancel: () => void;
};

/** What a choice sheet shows of an option: "Bica", "Água Pequena", "2 × Bica". */
function optionLabel(catalog: PosCatalog, option: MenuOption): string {
  const name = itemLabel(catalog, option.itemId, option.variantId) ?? 'Artigo';
  return Number(option.quantity) === 1 ? name : `${quantityLabel(option.quantity)} × ${name}`;
}

const groupTitle = ({ name, minChoices, maxChoices }: MenuGroup) =>
  minChoices === maxChoices ? `${name} (escolha ${minChoices})` : `${name} (escolha ${minChoices} a ${maxChoices})`;

const optionKey = (option: MenuOption) => `${option.itemId}:${option.variantId ?? ''}`;

/**
 * The questions an item asks before it is added (§4, PRD 40–41): its variant, when it has variants; each menu group's
 * choices, between the group's minimum and maximum; and an optional note. "Adicionar" waits until every answer is
 * within its limits; the server checks them again (`MENU_SELECTION_INVALID`).
 */
export function ChoiceSheet({ catalog, item, variantId: preset, onConfirm, onCancel }: Props) {
  const [variantId, setVariantId] = useState(preset);
  const [chosen, setChosen] = useState<Record<string, string[]>>({});
  const [notes, setNotes] = useState('');
  const variantMissing = item.variants.length > 0 && !variantId;
  const groupsMet = item.menuGroups.every((group) => {
    const count = chosen[group.code]?.length ?? 0;
    return count >= group.minChoices && count <= group.maxChoices;
  });

  function toggle(group: MenuGroup, option: MenuOption) {
    const key = optionKey(option);
    setChosen((current) => {
      const picked = current[group.code] ?? [];
      if (picked.includes(key)) return { ...current, [group.code]: picked.filter((candidate) => candidate !== key) };
      // A one-choice group swaps its choice; a fuller one takes no more than its maximum.
      if (group.maxChoices === 1) return { ...current, [group.code]: [key] };
      if (picked.length >= group.maxChoices) return current;
      return { ...current, [group.code]: [...picked, key] };
    });
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (variantMissing || !groupsMet) return;
    onConfirm({
      variantId,
      notes: notes.trim() || undefined,
      // In the menu's own order, group by group.
      components: item.menuGroups.flatMap((group) =>
        group.options
          .filter((option) => chosen[group.code]?.includes(optionKey(option)))
          .map((option) => ({ groupCode: group.code, itemId: option.itemId, variantId: option.variantId ?? undefined })),
      ),
    });
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onCancel()}>
      <DialogContent showCloseButton={false} className="max-h-[calc(100vh-2rem)] overflow-y-auto sm:max-w-xl">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle className="text-xl">{item.name}</DialogTitle>
            <DialogDescription>
              {item.menuGroups.length > 0 ? 'Escolha as opções do menu.' : 'Escolha a variante.'}
            </DialogDescription>
          </DialogHeader>
          {item.variants.length > 0 && (
            <fieldset className="flex flex-col gap-2">
              <legend className="mb-2 font-semibold">Variante</legend>
              <div className="grid grid-cols-2 gap-2">
                {item.variants.map((variant) => (
                  <label key={variant.id} className={CHOICE}>
                    <input
                      type="radio"
                      name="variant"
                      className="size-5 accent-primary"
                      checked={variantId === variant.id}
                      onChange={() => setVariantId(variant.id)}
                    />
                    <span className="flex-1">{variant.name}</span>
                    <span className="tabular-nums text-muted-foreground">{formatMoney(variant.price ?? item.price)}</span>
                  </label>
                ))}
              </div>
            </fieldset>
          )}
          {item.menuGroups.map((group) => {
            const picked = chosen[group.code] ?? [];
            const full = group.maxChoices > 1 && picked.length >= group.maxChoices;
            return (
              <fieldset key={group.code} className="flex flex-col gap-2">
                <legend className="mb-2 font-semibold">{groupTitle(group)}</legend>
                <div className="grid grid-cols-2 gap-2">
                  {group.options.map((option) => {
                    const checked = picked.includes(optionKey(option));
                    return (
                      <label key={optionKey(option)} className={CHOICE}>
                        <input
                          type="checkbox"
                          className="size-5 accent-primary"
                          checked={checked}
                          disabled={full && !checked}
                          onChange={() => toggle(group, option)}
                        />
                        <span className="flex-1">{optionLabel(catalog, option)}</span>
                      </label>
                    );
                  })}
                </div>
              </fieldset>
            );
          })}
          <NoteField value={notes} onChange={setNotes} />
          <DialogFooter>
            <Button type="button" variant="outline" size="touch" onClick={onCancel}>
              Cancelar
            </Button>
            <Button type="submit" size="touch-primary" disabled={variantMissing || !groupsMet}>
              Adicionar
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/** A 56 px choice the whole width of its cell: the label is the target, not only the box. */
const CHOICE =
  'flex min-h-14 cursor-pointer items-center gap-3 rounded-md border bg-background px-3 text-base has-checked:border-primary has-checked:bg-accent has-disabled:cursor-not-allowed has-disabled:opacity-50 has-focus-visible:ring-2 has-focus-visible:ring-ring';
