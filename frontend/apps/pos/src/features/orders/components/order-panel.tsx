import { Badge, Button, Money, type MoneyValue } from '@restaurantpro/ui';
import { Minus, NotebookPen, Plus, ReceiptText, Trash2 } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { useStoreTimeZone } from '../../../lib/auth/use-employee';
import { ScrollRegion } from '../../../lib/scroll-region';
import { clockTime, guestsLabel } from '../../dining/format';
import { itemLabel, type PosCatalog } from '../../catalog/catalog';
import { DestinationBadge } from '../../catalog/components/destination-badge';
import { type Order, type OrderLine, quantityLabel, sameAmount, stepQuantity } from '../order';

export type DraftCommands = {
  disabled: boolean;
  onQuantity: (line: OrderLine, quantity: string) => void;
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
  /** After a price or catalog conflict: each draft's gross as shown before, so a changed one says so. */
  shownBefore?: ReadonlyMap<string, MoneyValue | null>;
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
 * provisional price and the drafts' subtotal the server's provisional `draftGross`.
 */
export function OrderPanel({ order, catalog, commands, message, action, back, shownBefore }: Props) {
  const details = [order.guests !== null ? guestsLabel(order.guests) : undefined, `Pedido n.º ${order.orderNumber}`]
    .filter(Boolean)
    .join(' · ');
  return (
    <aside aria-label="Pedido" className="flex min-h-0 flex-col border-l bg-card">
      <header className="flex items-start gap-3 border-b px-4 py-3">
        {order.tableCode && (
          // Stitch's amber table block: the table is open, so occupied's colour.
          <span
            aria-hidden="true"
            className="flex size-12 shrink-0 items-center justify-center rounded-lg bg-warning text-lg font-bold text-warning-foreground tabular-nums"
          >
            {order.tableCode}
          </span>
        )}
        <div className="min-w-0 flex-1">
          <h2 className="truncate text-2xl font-semibold">{order.tableCode ? `Mesa ${order.tableCode}` : 'Pedido'}</h2>
          <p className="text-muted-foreground">{details}</p>
        </div>
        {back}
      </header>
      <ScrollRegion label="Linhas do pedido" className="flex min-h-0 flex-1 flex-col gap-4 px-4 py-3">
        {message && (
          <p role="alert" className="rounded-md border border-destructive px-3 py-2 font-medium text-error">
            {message}
          </p>
        )}
        <section aria-labelledby="sent-title" className="flex flex-col gap-2">
          <div className="flex items-baseline justify-between gap-2">
            <h3 id="sent-title" className="text-sm font-semibold tracking-wide text-muted-foreground uppercase">
              Enviado
            </h3>
            {order.rounds.length > 0 && (
              <span className="text-sm text-muted-foreground tabular-nums">
                {order.rounds.length === 1 ? '1 ronda' : `${order.rounds.length} rondas`}
              </span>
            )}
          </div>
          {order.rounds.length === 0 && order.unbatched.length === 0 ? (
            <p className="text-muted-foreground">Nada enviado ainda.</p>
          ) : (
            <SentRounds order={order} catalog={catalog} />
          )}
        </section>
        <section
          aria-labelledby="drafts-title"
          // Stitch: the draft round is dashed until it is sent.
          className="flex flex-col gap-2 rounded-lg border border-dashed border-primary p-3"
        >
          <h3 id="drafts-title" className="text-sm font-semibold tracking-wide text-primary uppercase">
            Por enviar
          </h3>
          {order.drafts.length === 0 ? (
            <p className="text-muted-foreground">Sem linhas por enviar.</p>
          ) : (
            <ul aria-label="Por enviar" className="flex flex-col">
              {order.drafts.map((line) => (
                <DraftRow
                  key={line.id}
                  line={line}
                  name={lineName(line, catalog)}
                  catalog={catalog}
                  commands={commands}
                  before={shownBefore?.has(line.id) ? { gross: shownBefore.get(line.id) ?? null } : undefined}
                />
              ))}
            </ul>
          )}
          {order.draftGross && (
            <div role="group" aria-label="Subtotal por enviar" className="flex items-baseline justify-between gap-2">
              <span className="font-semibold">Subtotal por enviar</span>
              <span className="flex shrink-0 flex-col items-end">
                <Money value={order.draftGross} className="font-semibold" />
                <Badge variant="secondary" className="text-xs">
                  Provisório
                </Badge>
              </span>
            </div>
          )}
        </section>
      </ScrollRegion>
      <footer className="flex flex-col gap-3 border-t px-4 py-3">
        <div role="group" aria-label="Total" className="flex flex-col">
          <div className="flex items-baseline justify-between">
            <span className="text-sm font-semibold tracking-wide text-muted-foreground uppercase">Total</span>
            <Money value={order.due} className="text-4xl font-bold text-primary" />
          </div>
          {/* Always one line high, so the button below never moves. */}
          <p className="min-h-5 text-sm text-muted-foreground">
            {order.drafts.length > 0 ? 'Não inclui as linhas por enviar' : ''}
          </p>
        </div>
        {action}
        <Button asChild variant="outline" size="touch" className="w-full">
          <Link to={`/orders/${order.id}/payment`}>
            <ReceiptText aria-hidden="true" />
            Conta & Pagamento
          </Link>
        </Button>
      </footer>
    </aside>
  );
}

/**
 * The confirmed lines, per round, then those outside any round; the order screen's "Enviado" and the table map's
 * order. Each round's list is named "Ronda N, HH:MM"; `heading` gives its visible heading another form,
 * `headingLevel` its level under the caller's own headings, and `badge` what follows the heading on its line (the table
 * map's kitchen state). `dense` (the table map's narrow panel): no card around a round, and rows without padding.
 */
export function SentRounds({
  order,
  catalog,
  heading = (batchNo, time) => `Ronda ${batchNo}, ${time}`,
  headingLevel: Heading = 'h4',
  badge,
  dense = false,
}: {
  order: Order;
  catalog?: PosCatalog;
  heading?: (batchNo: number, time: string) => string;
  headingLevel?: 'h3' | 'h4';
  badge?: (roundId: string) => ReactNode;
  dense?: boolean;
}) {
  const timeZone = useStoreTimeZone();
  const group = dense ? 'flex flex-col' : 'flex flex-col rounded-lg border bg-background p-3';
  return (
    <>
      {order.rounds.map((round) => {
        const time = clockTime(round.confirmedAt, timeZone);
        const title = `Ronda ${round.batchNo}, ${time}`;
        return (
          <div key={round.id} className={group}>
            {badge ? (
              <div className="flex flex-wrap items-center gap-x-2">
                <Heading className="text-sm font-semibold">{heading(round.batchNo, time)}</Heading> {badge(round.id)}
              </div>
            ) : (
              <Heading className="text-sm font-semibold">{heading(round.batchNo, time)}</Heading>
            )}
            <ul aria-label={title} className="flex flex-col">
              {round.lines.map((line) => (
                <SentRow key={line.id} line={line} name={lineName(line, catalog)} catalog={catalog} dense={dense} />
              ))}
            </ul>
          </div>
        );
      })}
      {order.unbatched.length > 0 && (
        <div className={group}>
          <Heading className="text-sm font-semibold">Sem ronda (transferidas)</Heading>
          <ul aria-label="Sem ronda" className="flex flex-col">
            {order.unbatched.map((line) => (
              <SentRow key={line.id} line={line} name={lineName(line, catalog)} catalog={catalog} dense={dense} />
            ))}
          </ul>
        </div>
      )}
    </>
  );
}

/** "2 ×", in the primary colour (Stitch's line count). */
function Quantity({ line }: { line: OrderLine }) {
  return <span className="font-bold text-primary tabular-nums">{quantityLabel(line.quantity)} ×</span>;
}

/** Where the line's item is prepared, from the catalog; nothing without a catalog or a destination. */
function Destination({ line, catalog }: { line: OrderLine; catalog: PosCatalog | undefined }) {
  return <DestinationBadge code={catalog?.item(line.itemId)?.productionDestination} />;
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

/** The same amount in the same currency (an equality, not arithmetic: "120.00" and 120 are one price). */
function sameMoney(a: MoneyValue | null, b: MoneyValue | null): boolean {
  if (!a || !b) return !a && !b;
  return a.currency === b.currency && sameAmount(a.amount, b.amount);
}

function PriceChange({ before, now }: { before: MoneyValue | null; now: MoneyValue | null }) {
  if (sameMoney(before, now)) return null;
  return (
    <p className="text-sm font-medium">
      Preço alterado
      {before && (
        <>
          , antes <Money value={before} />
        </>
      )}
    </p>
  );
}

function DraftRow({
  line,
  name,
  catalog,
  commands,
  before,
}: {
  line: OrderLine;
  name: string;
  catalog: PosCatalog | undefined;
  commands: DraftCommands;
  /** The gross shown before a price conflict, when there was one. */
  before?: { gross: MoneyValue | null };
}) {
  const { disabled, onQuantity, onRemove, onNote } = commands;
  const less = stepQuantity(line.quantity, -1);
  const more = stepQuantity(line.quantity, 1);
  return (
    <li className="flex flex-col gap-1 border-b py-2 last:border-b-0">
      <div className="flex items-start gap-2">
        <span className="flex-1 font-medium">
          <Quantity line={line} /> {name}
        </span>{' '}
        <Destination line={line} catalog={catalog} />
        <Price line={line} />
      </div>{' '}
      <Details line={line} catalog={catalog} />
      {before && <PriceChange before={before.gross} now={line.gross} />}
      <div className="flex gap-2">
        <Button
          variant="outline"
          size="touch-round"
          aria-label={`Diminuir ${name}`}
          disabled={disabled || less === undefined}
          onClick={() => less !== undefined && onQuantity(line, less)}
        >
          <Minus aria-hidden="true" />
        </Button>
        <Button variant="outline" size="touch-round" aria-label={`Aumentar ${name}`} disabled={disabled || more === undefined} onClick={() => more !== undefined && onQuantity(line, more)}>
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

function SentRow({
  line,
  name,
  catalog,
  dense,
}: {
  line: OrderLine;
  name: string;
  catalog: PosCatalog | undefined;
  dense: boolean;
}) {
  return (
    <li className={`flex flex-col gap-1 ${dense ? 'text-sm' : 'border-b py-2 last:border-b-0'}`}>
      <div className="flex items-start gap-2">
        <span className="flex-1">
          <Quantity line={line} /> {name}
        </span>{' '}
        <Destination line={line} catalog={catalog} />
        <Price line={line} />
      </div>{' '}
      <Details line={line} catalog={undefined} />
    </li>
  );
}
