import { ApiError } from '@restaurantpro/api-client';
import { Button, DataState, Money } from '@restaurantpro/ui';
import { Clock, ListPlus, Minus, Plus } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router';
import { useStoreTimeZone } from '../../../lib/auth/use-employee';
import { isOffline } from '../../../lib/error-messages';
import { useCommandsBlocked } from '../../../lib/live/use-connection';
import { ScrollRegion } from '../../../lib/scroll-region';
import { useCatalog } from '../../catalog/hooks/use-catalog';
import { clockTime } from '../../dining/format';
import { roundRequest, useConfirmBatch } from '../hooks/use-confirm-batch';
import { useOrder } from '../hooks/use-order';
import { useDraftLineCommands } from '../hooks/use-order-commands';
import { nextRoundNo, type Order, type OrderLine, quantityLabel, stepQuantity } from '../order';
import { draftMessage } from './order-screen';
import { lineName } from './order-panel';
import { SendRound } from './send-round';

type Props = {
  orderId: string;
  /** "Adicionar artigos": items are added on the order screen, which has the catalog. */
  onAddItems: () => void;
};

/**
 * The selected table's open order on the table map (light reference, 2026-11-23): its rounds as sent, the drafts as the
 * next round with quantity steppers, the server's total, the ways to the bill and "Enviar ronda N". The order screen's
 * queries and commands, the same intent and key per "Enviar"; nothing is shown as changed before the server says so.
 */
export function TableOrder({ orderId, onAddItems }: Props) {
  const order = useOrder(orderId);
  if (order.data) return <OrderBody order={order.data} onAddItems={onAddItems} />;
  return (
    <div className="p-4">
      <DataState
        state={
          order.isError ? (order.error instanceof ApiError && isOffline(order.error) ? 'unavailable' : 'error') : 'loading'
        }
        onRetry={() => void order.refetch()}
      >
        {null}
      </DataState>
    </div>
  );
}

function OrderBody({ order, onAddItems }: { order: Order; onAddItems: () => void }) {
  const catalog = useCatalog();
  const blocked = useCommandsBlocked();
  const timeZone = useStoreTimeZone();
  const { update } = useDraftLineCommands(order.id);
  // The round number the last "Enviar" got from the server (null: an answer without it).
  const [sentBatchNo, setSentBatchNo] = useState<number | null>();
  const send = useConfirmBatch(order.id, { onSent: (batch) => setSentBatchNo(batch?.batchNo ?? null) });
  const canCommand = order.lifecycle === 'OPEN' && !blocked;
  const next = nextRoundNo(order);
  // The draft whose change is with the server: shown as "a confirmar" until the order read again says what it is.
  const stepping = update.isPending ? update.variables?.lineId : undefined;

  /** Any other command makes the last send's outcome old news; an unknown outcome keeps its key for a retry. */
  function forgetLastSend() {
    setSentBatchNo(undefined);
    if (!send.intent) send.discard();
  }

  function sendRound() {
    forgetLastSend();
    const request = roundRequest(order);
    if (request) send.start(...request);
  }

  function step(line: OrderLine, quantity: string) {
    forgetLastSend();
    update.mutate({
      lineId: line.id,
      body: { version: line.version, quantity, variantId: line.variantId ?? undefined, notes: line.notes ?? undefined },
    });
  }

  const canSend = canCommand && order.drafts.length > 0 && order.catalogVersion !== null && !update.isPending;
  // A draft is named from the catalog: while it loads the name waits, rather than reading "fora do catálogo".
  const nameOf = (line: OrderLine) =>
    line.status === 'DRAFT' && catalog.isPending ? 'A carregar…' : lineName(line, catalog.data);

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <ScrollRegion label="Linhas do pedido" className="flex min-h-0 flex-1 flex-col gap-4 px-4 py-3">
        {update.error && (
          <p role="alert" className="rounded-lg border border-destructive px-3 py-2 font-medium text-error">
            {draftMessage(update.error)}
          </p>
        )}
        {order.rounds.length === 0 && order.unbatched.length === 0 ? (
          <p className="text-muted-foreground">Nada enviado ainda.</p>
        ) : (
          <>
            {order.rounds.map((round) => {
              const time = clockTime(round.confirmedAt, timeZone);
              return (
                <section key={round.id} className="flex flex-col gap-1">
                  {/* Every round the server confirmed has been sent to production; its kitchen state is not on the order. */}
                  <h3 className="text-sm font-medium text-muted-foreground">
                    Ronda {round.batchNo} · enviada · {time}
                  </h3>
                  <ul aria-label={`Ronda ${round.batchNo}, ${time}`} className="flex flex-col">
                    {round.lines.map((line) => (
                      <SentLine key={line.id} line={line} name={nameOf(line)} />
                    ))}
                  </ul>
                </section>
              );
            })}
            {order.unbatched.length > 0 && (
              <section className="flex flex-col gap-1">
                <h3 className="text-sm font-medium text-muted-foreground">Sem ronda (transferidas)</h3>
                <ul aria-label="Sem ronda" className="flex flex-col">
                  {order.unbatched.map((line) => (
                    <SentLine key={line.id} line={line} name={nameOf(line)} />
                  ))}
                </ul>
              </section>
            )}
          </>
        )}
        <section aria-labelledby="table-draft-title" className="flex flex-col gap-2">
          <h3 id="table-draft-title" className="text-sm font-medium text-muted-foreground">
            Ronda {next} · rascunho
          </h3>
          {order.drafts.length === 0 ? (
            <p className="text-muted-foreground">Sem linhas por enviar.</p>
          ) : (
            <ul aria-label="Por enviar" className="flex flex-col gap-1">
              {order.drafts.map((line) => (
                <DraftLine
                  key={line.id}
                  line={line}
                  name={nameOf(line)}
                  pending={line.id === stepping}
                  disabled={!canCommand || update.isPending || send.pending}
                  onStep={(quantity) => step(line, quantity)}
                />
              ))}
            </ul>
          )}
          <Button variant="outline" size="touch" className="w-full rounded-xl bg-card" onClick={onAddItems}>
            <ListPlus aria-hidden="true" />
            Adicionar artigos
          </Button>
        </section>
      </ScrollRegion>
      <footer className="flex flex-col gap-2 border-t px-4 pt-3">
        {blocked && <p className="text-sm font-medium text-error">Sem ligação — nenhuma ronda é enviada.</p>}
        <div>
          <div role="group" aria-label="Total" className="flex items-baseline justify-between gap-2">
            <span className="text-muted-foreground">Total</span>
            <Money value={order.due} className="text-3xl font-bold" />
          </div>
          {/* Always one line high, so the buttons below never move. */}
          <p className="min-h-5 text-sm text-muted-foreground">
            {order.drafts.length > 0 ? 'Não inclui as linhas por enviar' : ''}
          </p>
        </div>
        <div className="grid grid-cols-2 gap-2">
          <Button asChild variant="outline" size="touch" className="rounded-xl bg-card">
            <Link to={`/orders/${order.id}/payment`}>Subtotal</Link>
          </Button>
          <Button asChild variant="outline" size="touch" className="rounded-xl bg-card">
            <Link to={`/orders/${order.id}/payment`}>Dividir</Link>
          </Button>
        </div>
        <SendRound
          send={send}
          disabled={!canSend}
          blocked={!canCommand}
          sentBatchNo={sentBatchNo}
          onSend={sendRound}
          label={`Enviar ronda ${next}`}
        />
      </footer>
    </div>
  );
}

function SentLine({ line, name }: { line: OrderLine; name: string }) {
  return (
    <li className="flex items-baseline justify-between gap-2 py-1">
      <span>
        {quantityLabel(line.quantity)}× {name}
      </span>{' '}
      {line.gross ? <Money value={line.gross} className="shrink-0" /> : <span className="shrink-0">Sem preço</span>}
    </li>
  );
}

/** A draft: its name and provisional price, and − / quantity / +; "−" stops at one (removing is on the order screen). */
function DraftLine({
  line,
  name,
  pending,
  disabled,
  onStep,
}: {
  line: OrderLine;
  name: string;
  pending: boolean;
  disabled: boolean;
  onStep: (quantity: string) => void;
}) {
  const less = stepQuantity(line.quantity, -1);
  const more = stepQuantity(line.quantity, 1);
  return (
    <li className={`flex items-center gap-2 rounded-lg px-2 py-1 ${pending ? 'bg-warning-tint text-warning-text' : ''}`}>
      <div className="min-w-0 flex-1">
        <p className="font-medium">
          {quantityLabel(line.quantity)}× {name}
        </p>{' '}
        <p className={`flex flex-wrap items-center gap-1 text-sm ${pending ? '' : 'text-muted-foreground'}`}>
          {pending && (
            <>
              <Clock aria-hidden="true" className="size-4" />
              <span>a confirmar</span>
              <span aria-hidden="true">·</span>{' '}
            </>
          )}
          {line.gross ? <Money value={line.gross} /> : <span>Sem preço</span>}
        </p>
      </div>{' '}
      <div role="group" aria-label={`Quantidade de ${name}`} className="flex shrink-0 items-center gap-1">
        <Button
          variant="outline"
          size="touch"
          className="rounded-full bg-card px-0"
          aria-label={`Diminuir ${name}`}
          disabled={disabled || less === undefined}
          onClick={() => less !== undefined && onStep(less)}
        >
          <Minus aria-hidden="true" />
        </Button>
        <span aria-hidden="true" className="min-w-8 text-center font-semibold tabular-nums">
          {quantityLabel(line.quantity)}
        </span>
        <Button
          variant="outline"
          size="touch"
          className="rounded-full bg-card px-0"
          aria-label={`Aumentar ${name}`}
          disabled={disabled || more === undefined}
          onClick={() => more !== undefined && onStep(more)}
        >
          <Plus aria-hidden="true" />
        </Button>
      </div>
    </li>
  );
}
