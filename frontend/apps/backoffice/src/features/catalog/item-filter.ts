import type { ItemSummary } from './types';

export type ItemFilter = { search: string; familyId: string | undefined };

/**
 * The items of the family (all when none) whose name or code contains the search, ignoring case and accents. In the
 * browser because the contract's item list takes no filter (gap CAT-1).
 */
export function filterItems(items: readonly ItemSummary[], { search, familyId }: ItemFilter): ItemSummary[] {
  const needle = fold(search.trim());
  return items.filter(
    (item) =>
      (!familyId || item.familyId === familyId) &&
      (!needle || fold(item.name ?? '').includes(needle) || fold(item.code ?? '').includes(needle)),
  );
}

/** How many of the items each family holds, for the family pills. */
export function countByFamily(items: readonly ItemSummary[]): Map<string, number> {
  const counts = new Map<string, number>();
  for (const item of items) {
    if (item.familyId) counts.set(item.familyId, (counts.get(item.familyId) ?? 0) + 1);
  }
  return counts;
}

function fold(text: string): string {
  return text.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase();
}
