import { Button } from '@restaurantpro/ui';

type PagerProps = { page: number; totalElements: number; pageSize: number; onPage: (page: number) => void };

/** Server-side paging (§9): previous and next over the server's pages; nothing when there is one page. */
export function Pager({ page, totalElements, pageSize, onPage }: PagerProps) {
  const pages = Math.ceil(totalElements / pageSize);
  if (pages <= 1) return null;
  return (
    <nav aria-label="Páginas" className="flex items-center justify-end gap-2 text-sm">
      <Button variant="outline" size="sm" disabled={page === 0} onClick={() => onPage(page - 1)}>
        Anterior
      </Button>
      <span>
        Página {page + 1} de {pages}
      </span>
      <Button variant="outline" size="sm" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>
        Seguinte
      </Button>
    </nav>
  );
}
