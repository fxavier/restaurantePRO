import { cn, TONE_CARD, TONE_INK } from '@restaurantpro/ui';
import { TriangleAlert } from 'lucide-react';
import type { ListPage } from '../hooks/use-catalog-queries';

/**
 * Says a list read as one page holds fewer rows than the server has (gap CAT-1: no server-side search or filter yet),
 * so a row past the page is neither shown nor found. Nothing while the list is whole or not yet read. The warning's
 * tint and border with its AA text, as the POS's alerts.
 */
export function CutListNotice({ list, what }: { list: ListPage<unknown> | undefined; what: string }) {
  if (!list || list.totalElements <= list.content.length) return null;
  return (
    <p role="note" className={cn('flex items-center gap-2 rounded-xl border px-3 py-2 text-sm font-medium', TONE_CARD.warning, TONE_INK.warning)}>
      <TriangleAlert aria-hidden="true" className="size-4 shrink-0" />
      Lista incompleta: mostra {list.content.length} de {list.totalElements} {what}; o resto não aparece aqui nem na pesquisa.
    </p>
  );
}
