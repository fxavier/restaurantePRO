import { Button, TONE_CARD, TONE_INK } from '@restaurantpro/ui';
import { DoorOpen, ListPlus, X } from 'lucide-react';
import { type ReactNode, useEffect, useRef } from 'react';
import { useStoreTimeZone } from '../../../lib/auth/use-employee';
import { clockTime, guestsLabel, seatsLabel } from '../format';
import type { DiningTable } from '../table-map';
import { tableState } from '../table-state';
import { indicators, STATE_TONE } from './table-tile';

type Props = {
  /** The selected table; none yet before the first tap. */
  table: DiningTable | undefined;
  areaName: string | undefined;
  /** A free table: "Abrir mesa" (the open-table dialog). */
  onOpenTable: (table: DiningTable) => void;
  /** An occupied table the map gives no order for: "Adicionar artigos" (its own page says why). */
  onGoToOrder: (table: DiningTable) => void;
  /** Below `lg` the panel covers the bottom of the map; "Fechar" clears the selection. */
  onClose: () => void;
  /** An occupied table's open order (the page composes it from the orders feature). */
  children?: ReactNode;
};

/**
 * The selected table (the light reference's right card): its code, guests, opening time and indicators; then an
 * occupied table's order (`children`) or a free table's "Abrir mesa". A column from `lg`; a panel over the bottom of
 * the map below.
 */
export function TableDetailPanel({ table, areaName, onOpenTable, onGoToOrder, onClose, children }: Props) {
  const heading = useRef<HTMLHeadingElement>(null);
  const timeZone = useStoreTimeZone();

  // Selecting a table takes keyboard and screen-reader users to its detail.
  useEffect(() => {
    if (table) heading.current?.focus();
  }, [table?.id]); // eslint-disable-line react-hooks/exhaustive-deps -- only a new selection moves focus, not a refresh

  const shell = `${table ? 'flex' : 'hidden lg:flex'} fixed inset-x-0 bottom-0 z-40 max-h-2/3 flex-col border-t bg-card lg:static lg:max-h-none lg:w-96 lg:shrink-0 lg:rounded-2xl lg:border`;

  if (!table) {
    return (
      <aside aria-label="Detalhe da mesa" className={`${shell} items-center justify-center p-6`}>
        <p className="text-center text-muted-foreground">Toque numa mesa para ver o detalhe.</p>
      </aside>
    );
  }

  const occupied = table.status === 'OCCUPIED';
  const meta = [areaName, occupied && table.guests !== null ? guestsLabel(table.guests) : seatsLabel(table.seats)]
    .filter(Boolean)
    .join(' · ');
  const badges = indicators(table, timeZone);
  // The code block: the state's tint and text, as on the tile (DESIGN.md "Light reference").
  const tone = STATE_TONE[tableState(table)];

  return (
    <aside aria-label="Detalhe da mesa" className={shell}>
      {/* Compact (light reference): at 1024 × 768 the order under it shows three drafts without scrolling. */}
      <header className="flex flex-col gap-1 border-b px-4 py-2">
        <div className="flex items-start gap-3">
          <span
            aria-hidden="true"
            className={`flex size-10 shrink-0 items-center justify-center rounded-xl border-2 text-lg font-bold tabular-nums ${TONE_CARD[tone]} ${TONE_INK[tone]}`}
          >
            {table.code}
          </span>
          <div className="min-w-0 flex-1">
            <h2 ref={heading} tabIndex={-1} className="truncate text-base font-semibold focus-visible:outline-none">
              Mesa {table.code}
            </h2>
            {/* One line: where and how many, the state (its term read out only; the code block's tint shows it), the opening. */}
            <div className="flex flex-wrap gap-x-3 text-sm">
              <p className="text-muted-foreground">{meta}</p>
              <dl className="flex flex-wrap gap-x-3">
                <div className="flex gap-1">
                  <dt className="sr-only">Estado</dt>
                  <dd className="font-semibold">{occupied ? 'Ocupada' : 'Livre'}</dd>
                </div>
                {table.openedAt && (
                  <div className="flex gap-1">
                    <dt className="text-muted-foreground">Abertura</dt>
                    <dd className="font-semibold tabular-nums">{clockTime(table.openedAt, timeZone)}</dd>
                  </div>
                )}
              </dl>
            </div>
          </div>
          <Button variant="ghost" size="touch" aria-label="Fechar" className="lg:hidden" onClick={onClose}>
            <X aria-hidden="true" />
          </Button>
        </div>
        {badges.length > 0 && (
          <ul aria-label="Indicadores" className="flex flex-wrap gap-x-3 gap-y-1 text-sm font-medium">
            {badges.map((badge) => (
              <li key={badge.label} className={`flex items-center gap-1 [&>svg]:size-4 ${TONE_INK[badge.variant]}`}>
                {badge.icon}
                {badge.label}
              </li>
            ))}
          </ul>
        )}
      </header>
      {occupied && children ? (
        children
      ) : (
        <footer className="mt-auto flex flex-col gap-2 p-4">
          {occupied ? (
            <Button size="touch-primary" className="w-full" onClick={() => onGoToOrder(table)}>
              <ListPlus aria-hidden="true" />
              Adicionar artigos
            </Button>
          ) : (
            <Button size="touch-primary" className="w-full" onClick={() => onOpenTable(table)}>
              <DoorOpen aria-hidden="true" />
              Abrir mesa
            </Button>
          )}
        </footer>
      )}
    </aside>
  );
}
