import { Badge, Button, Money } from '@restaurantpro/ui';
import { Minus, NotebookPen, Plus, Trash2 } from 'lucide-react';
import type { ReactNode } from 'react';
import { clockTime, guestsLabel } from '../../dining/format';
import { itemLabel, type PosCatalog } from '../../catalog/catalog';
import { type Order, type OrderLine, quantityLabel } from '../order';

export type DraftCommands = {
  disabled: boolean;
  onQuantity: (line: OrderLine, quantity: number) => void;
  onRemove: (line: OrderLine) => void;
  onNote: (line: OrderLine) => void;
};

type Props = {
  order: Order;
  catalog: PosCatalog | undefined;
  commands: DraftCommands;
  /** A refused or conflicting change of a draft, shown above the lines. */
  message?: string;
  /** The primary action's place (DS: one 56 px "Pedir"), kept so nothing moves when it arrives. */
  action: ReactNode;
  /** The way back to the tables, beside the order's title. */
  back: ReactNode;
};

/** A draft's name comes from the catalog (a draft has no snapshot); a confirmed line's from its snapshot. */
export function lineName(line: OrderLine, catalog: PosCatalog | undefined): string {
  if (line.status === 'CONFIRMED' && line.description) {
    return line.variantName ? `${line.description} ${line.variantName}` : line.description;
  }
  return (catalog && itemLabel(catalog, line.itemId, line.variantId)) ?? line.description ?? 'Artigo fora do catálogo';
}

/**
 * The order panel (DS: right, 360 px; §4, §8.3, PRD 42–44): "Por enviar" (the drafts, which change or go) and
 * "Enviado" (the confirmed lines, per round), then the server's total. Every amount is the server's; a draft's is its
 * provisional price.
 */
export function OrderPanel({ order, catalog, commands, message, action, back }: Props) {
  const details = [order.guests !== undefined ? guestsLabel(order.guests) : undefined, order.orderNumber !== undefined ? `Pedido n.º ${order.orderNumber}` : undefined]
    .filter(Boolean)
    .join(' · ');
  return (
    <aside aria-label="Pedido" className="flex min-h-0 flex-col border-l bg-background">
      <header className="flex items-start justify-between gap-2 border-b px-4 py-3">
        <div className="min-w-0">
          <h2 className="truncate text-2xl font-semibold">{order.tableCode ? `Mesa ${order.tableCode}` : 'Pedido'}</h2>
          <p className="text-muted-foreground">{details}</p>
        </div>
        {back}
      </header>
      <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-4 py-3">
        {message && (
          <p role="alert" className="rounded-md border border-destructive px-3 py-2 font-medium text-destructive">
            {message}
          </p>
        )}
        <section aria-labelledby="drafts-title" className="flex flex-col gap-2">
          <h3 id="drafts-title" className="text-lg font-semibold">
            Por enviar
          </h3>
          {order.drafts.length === 0 ? (
            <p className="text-muted-foreground">Sem linhas por enviar.</p>
          ) : (
            <ul aria-label="Por enviar" className="flex flex-col">
              {order.drafts.map((line) => (
                <DraftRow key={line.id} line={line} name={lineName(line, catalog)} catalog={catalog} commands={commands} />
              ))}
            </ul>
          )}
        </section>
        <section aria-labelledby="sent-title" className="flex flex-col gap-2">
          <h3 id="sent-title" className="text-lg font-semibold">
            Enviado
          </h3>
          {order.rounds.length === 0 && order.unbatched.length === 0 ? (
            <p className="text-muted-foreground">Nada enviado ainda.</p>
          ) : (
            <>
              {order.rounds.map((round) => {
                const title = round.confirmedAt ? `Ronda ${round.batchNo}, ${clockTime(round.confirmedAt)}` : `Ronda ${round.batchNo}`;
                return (
                  <div key={round.id} className="flex flex-col">
                    <h4 className="text-sm font-semibold text-muted-foreground">{title}</h4>
                    <ul aria-label={title} className="flex flex-col">
                      {round.lines.map((line) => (
                        <SentRow key={line.id} line={line} name={lineName(line, catalog)} />
                      ))}
                    </ul>
                  </div>
                );
              })}
              {order.unbatched.length > 0 && (
                <div className="flex flex-col">
                  <h4 className="text-sm font-semibold text-muted-foreground">Sem ronda (transferidas)</h4>
                  <ul aria-label="Sem ronda" className="flex flex-col">
                    {order.unbatched.map((line) => (
                      <SentRow key={line.id} line={line} name={lineName(line, catalog)} />
                    ))}
                  </ul>
                </div>
              )}
            </>
          )}
        </section>
      </div>
      <footer className="flex flex-col gap-3 border-t px-4 py-3">
        <div role="group" aria-label="Total" className="flex flex-col">
          <div className="flex items-baseline justify-between">
            <span className="text-lg font-semibold">Total</span>
            {order.due ? <Money value={order.due} className="text-[28px] leading-9 font-bold" /> : <span>—</span>}
          </div>
          {/* Always one line high, so the button below never moves. */}
          <p className="min-h-5 text-sm text-muted-foreground">
            {order.drafts.length > 0 ? 'Não inclui as linhas por enviar' : ''}
          </p>
        </div>
        {action}
      </footer>
    </aside>
  );
}

function Price({ line }: { line: OrderLine }) {
  return (
    <span className="flex shrink-0 flex-col items-end">
      {line.gross ? <Money value={line.gross} className="font-medium" /> : <span>Sem preço</span>}
      {line.preview && (
        <Badge variant="secondary" className="text-xs">
          Preço provisório
        </Badge>
      )}
    </span>
  );
}

function Details({ line, catalog }: { line: OrderLine; catalog: PosCatalog | undefined }) {
  const components = line.components
    .map((component) => component.description ?? (catalog && itemLabel(catalog, component.itemId, component.variantId)))
    .filter(Boolean);
  return (
    <>
      {components.length > 0 && <p className="text-sm text-muted-foreground">{components.join(', ')}</p>}
      {line.notes && <p className="text-sm">Nota: {line.notes}</p>}
    </>
  );
}

function DraftRow({ line, name, catalog, commands }: { line: OrderLine; name: string; catalog: PosCatalog | undefined; commands: DraftCommands }) {
  const { disabled, onQuantity, onRemove, onNote } = commands;
  return (
    <li className="flex flex-col gap-1 border-b py-2 last:border-b-0">
      <div className="flex items-start justify-between gap-2">
        <span className="font-medium">
          {quantityLabel(line.quantity)} × {name}
        </span>{' '}
        <Price line={line} />
      </div>{' '}
      <Details line={line} catalog={catalog} />
      <div className="flex gap-2">
        <Button
          variant="outline"
          size="touch"
          aria-label={`Diminuir ${name}`}
          disabled={disabled || line.quantity <= 1}
          onClick={() => onQuantity(line, line.quantity - 1)}
        >
          <Minus aria-hidden="true" />
        </Button>
        <Button variant="outline" size="touch" aria-label={`Aumentar ${name}`} disabled={disabled} onClick={() => onQuantity(line, line.quantity + 1)}>
          <Plus aria-hidden="true" />
        </Button>
        <Button variant="outline" size="touch" aria-label={`Nota de ${name}`} disabled={disabled} onClick={() => onNote(line)}>
          <NotebookPen aria-hidden="true" />
        </Button>
        <Button variant="outline" size="touch" className="ml-auto" aria-label={`Remover ${name}`} disabled={disabled} onClick={() => onRemove(line)}>
          <Trash2 aria-hidden="true" />
        </Button>
      </div>
    </li>
  );
}

function SentRow({ line, name }: { line: OrderLine; name: string }) {
  return (
    <li className="flex flex-col gap-1 border-b py-2 last:border-b-0">
      <div className="flex items-start justify-between gap-2">
        <span>
          {quantityLabel(line.quantity)} × {name}
        </span>{' '}
        <Price line={line} />
      </div>{' '}
      <Details line={line} catalog={undefined} />
    </li>
  );
}
