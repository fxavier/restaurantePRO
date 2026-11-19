import { ApiError } from '@restaurantpro/api-client';
import { Button, Input, Skeleton } from '@restaurantpro/ui';
import { RotateCcw, Search, X } from 'lucide-react';
import { type FormEvent, type ReactNode, useEffect, useRef, useState } from 'react';
import { errorMessage, isOffline, isVersionConflict } from '../../../lib/error-messages';
import { isInProgress } from '../../../lib/use-intent';
import { byCode, type CatalogItem, itemLabel, needsChoices, search } from '../../catalog/catalog';
import { FamilyRail } from '../../catalog/components/family-rail';
import { ProductGrid } from '../../catalog/components/product-grid';
import { useCatalog } from '../../catalog/hooks/use-catalog';
import { isCatalogConflict, type Shown, useConfirmBatch } from '../hooks/use-confirm-batch';
import { useAddLine, useDraftLineCommands } from '../hooks/use-order-commands';
import type { Order, OrderLine } from '../order';
import { ChoiceSheet, type Choices } from './choice-sheet';
import { NoteDialog } from './note-dialog';
import { lineName, OrderPanel } from './order-panel';
import { SendRound } from './send-round';

type Props = {
  order: Order;
  /** The way back to the tables. */
  back: ReactNode;
  /** "Sem ligação": every command is disabled (FC §11). */
  blocked: boolean;
};

type Choosing = { item: CatalogItem; variantId?: string };

/**
 * The order screen (DS: family rail 160 px | product grid with search | order panel 360 px). A tap on a product is
 * one intent; an item with a variant or a menu asks first. Nothing here computes an amount: the panel shows the
 * server's order, read again after every command and every `ORDER` change.
 */
export function OrderScreen({ order, blocked, back }: Props) {
  const catalog = useCatalog();
  const add = useAddLine(order.id);
  const { update, remove } = useDraftLineCommands(order.id);
  const [family, setFamily] = useState<string>();
  const [query, setQuery] = useState('');
  const [notice, setNotice] = useState<string>();
  const [choosing, setChoosing] = useState<Choosing>();
  const [noteFor, setNoteFor] = useState<OrderLine>();
  // What the last "Pedir" did: the round number the server answered (null: an answer without it), and after a price
  // or catalog conflict, the prices shown before it, beside the new ones.
  const [sentBatchNo, setSentBatchNo] = useState<number | null>();
  const [shownBefore, setShownBefore] = useState<Shown['gross']>();
  const send = useConfirmBatch(order.id, {
    onSent: (batch) => setSentBatchNo(batch?.batchNo ?? null),
    onRefused: (error, shown) => setShownBefore(isCatalogConflict(error) ? shown.gross : undefined),
  });
  const searchField = useRef<HTMLInputElement>(null);
  const open = order.lifecycle === 'OPEN';
  const canCommand = open && !blocked;

  // The scanner types where the focus is: the search field, from the moment the products show.
  const loaded = catalog.data !== undefined;
  useEffect(() => {
    if (loaded) searchField.current?.focus();
  }, [loaded]);

  const shownFamily = family ?? catalog.data?.families[0]?.id;

  /** Any other command makes the last "Pedir"'s outcome old news. */
  function forgetLastSend() {
    setSentBatchNo(undefined);
    setShownBefore(undefined);
    // A refusal is cleared; an unknown outcome stays, with its key, for "Tentar novamente".
    if (!send.intent) send.discard();
  }

  function addLine(item: CatalogItem, choices: Choices = {}) {
    forgetLastSend();
    const label = itemLabel(catalog.data!, item.id, choices.variantId) ?? item.name;
    add.start({ itemId: item.id, quantity: '1', components: [], ...choices }, { label });
  }

  function pick(item: CatalogItem, variantId?: string) {
    setNotice(undefined);
    const answered = variantId !== undefined && item.menuGroups.length === 0;
    if (needsChoices(item) && !answered) setChoosing({ item, variantId });
    else addLine(item, { variantId });
  }

  function enterCode(event: FormEvent) {
    event.preventDefault();
    if (!catalog.data || !query.trim() || !canCommand || add.intent) return;
    const match = byCode(catalog.data.items, query);
    if (match) {
      setQuery('');
      pick(match.item, match.variantId);
    } else {
      setNotice(`Nenhum artigo com o código ${query.trim()}.`);
    }
  }

  function pedir() {
    forgetLastSend();
    if (order.catalogVersion === undefined) return;
    send.start(
      {
        orderVersion: order.version,
        catalogVersion: order.catalogVersion,
        promotionVersion: order.promotionVersion,
        lineIds: order.drafts.map((line) => line.id),
        // D104: the server's draftGross echoed as given (its decimal string, verbatim), so a round that would now cost
        // anything else is refused with PRICES_CHANGED (a draft changed on another terminal, a promotion window
        // closed). String() only matters for a number replayed from before decimals became strings (FE-F33).
        expectedGross: order.draftGross && String(order.draftGross.amount),
      },
      { gross: new Map(order.drafts.map((line) => [line.id, line.gross])) },
    );
  }

  const draftCommands = {
    disabled: !canCommand || update.isPending || remove.isPending || send.pending,
    onQuantity: (line: OrderLine, quantity: string) => {
      forgetLastSend();
      update.mutate({
        lineId: line.id,
        body: { version: line.version, quantity, variantId: line.variantId, notes: line.notes },
      });
    },
    onRemove: (line: OrderLine) => {
      forgetLastSend();
      remove.mutate({ lineId: line.id, version: line.version });
    },
    onNote: (line: OrderLine) => setNoteFor(line),
  };
  const draftError = update.error ?? remove.error;
  const canSend =
    canCommand &&
    order.drafts.length > 0 &&
    order.catalogVersion !== undefined &&
    add.intent === undefined &&
    !update.isPending &&
    !remove.isPending;

  return (
    <div className="grid min-h-0 flex-1 grid-cols-[160px_minmax(0,1fr)_360px]">
      <FamilyRail
        families={catalog.data?.families ?? []}
        selected={query.trim() ? undefined : shownFamily}
        onSelect={(id) => {
          setFamily(id);
          setQuery('');
        }}
      />
      <section aria-label="Produtos" className="flex min-h-0 flex-col gap-3 p-3">
        <form role="search" onSubmit={enterCode} className="relative">
          <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 left-3 size-5 -translate-y-1/2 text-muted-foreground" />
          <Input
            ref={searchField}
            type="search"
            aria-label="Pesquisar artigo ou código"
            placeholder="Pesquisar artigo ou código"
            autoComplete="off"
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
              setNotice(undefined);
            }}
            className="h-12 bg-background pl-10 text-base md:text-base"
          />
        </form>
        {/* One fixed line for what the last tap did, so the grid never moves when it changes. */}
        <div className="flex min-h-12 items-center">
          <AddStatus add={add} blocked={!canCommand} notice={notice} />
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto">
          {catalog.data ? (
            <ProductGrid
              items={
                query.trim()
                  ? search(catalog.data.items, query)
                  : catalog.data.items.filter((item) => catalog.data.familyOf(item) === shownFamily)
              }
              disabled={!canCommand || add.intent !== undefined}
              onPick={(item) => pick(item)}
            />
          ) : catalog.isError ? (
            <div className="flex flex-col items-center gap-3 py-6 text-center">
              <p>{catalogError(catalog.error)}</p>
              <Button variant="outline" size="touch" onClick={() => void catalog.refetch()}>
                Tentar novamente
              </Button>
            </div>
          ) : (
            <div role="status" aria-label="A carregar os artigos…" className="grid grid-cols-[repeat(auto-fill,minmax(7rem,1fr))] gap-2">
              {Array.from({ length: 12 }, (_, index) => (
                <Skeleton key={index} className="h-24 rounded-lg" />
              ))}
            </div>
          )}
        </div>
      </section>
      <OrderPanel
        order={order}
        catalog={catalog.data}
        commands={draftCommands}
        back={back}
        message={!open ? closedMessage(order) : draftError ? draftMessage(draftError) : undefined}
        shownBefore={shownBefore}
        action={<SendRound send={send} disabled={!canSend} blocked={!canCommand} sentBatchNo={sentBatchNo} onSend={pedir} />}
      />
      {choosing && catalog.data && (
        <ChoiceSheet
          catalog={catalog.data}
          item={choosing.item}
          variantId={choosing.variantId}
          onCancel={() => setChoosing(undefined)}
          onConfirm={(choices) => {
            setChoosing(undefined);
            addLine(choosing.item, choices);
          }}
        />
      )}
      {noteFor && (
        <NoteDialog
          name={lineName(noteFor, catalog.data)}
          notes={noteFor.notes}
          onCancel={() => setNoteFor(undefined)}
          onSave={(notes) => {
            forgetLastSend();
            update.mutate({
              lineId: noteFor.id,
              body: { version: noteFor.version, quantity: noteFor.quantity, variantId: noteFor.variantId, notes },
            });
            setNoteFor(undefined);
          }}
        />
      )}
    </div>
  );
}

function AddStatus({ add, blocked, notice }: { add: ReturnType<typeof useAddLine>; blocked: boolean; notice?: string }) {
  const label = add.intent?.meta.label;
  if (add.pending) {
    return (
      <p role="status" aria-label="A adicionar" className="text-muted-foreground">
        A adicionar {label}…
      </p>
    );
  }
  if (add.error && add.intent) {
    // An unknown outcome: the same intent may be sent again, with its key, or given up.
    return (
      <div role="alert" className="flex w-full items-center gap-2">
        <p className="flex-1 font-medium text-destructive">
          {isInProgress(add.error) ? `${label} ainda está a ser processado pelo servidor.` : `Sem ligação — ${label} não foi confirmado.`}
        </p>
        <Button variant="outline" size="touch" onClick={add.retry} disabled={blocked}>
          <RotateCcw aria-hidden="true" />
          Tentar novamente
        </Button>
        <Button variant="ghost" size="touch" onClick={add.discard}>
          <X aria-hidden="true" />
          Descartar
        </Button>
      </div>
    );
  }
  if (add.error) {
    return (
      <p role="alert" className="font-medium text-destructive">
        {`${add.last?.meta.label ?? 'O artigo'} não foi adicionado. ${addRefusal(add.error)}`}
      </p>
    );
  }
  if (notice) {
    return (
      <p role="alert" className="font-medium text-destructive">
        {notice}
      </p>
    );
  }
  return null;
}

function addRefusal(error: Error): string {
  return error instanceof ApiError ? errorMessage(error) : 'A resposta do servidor está incompleta.';
}

function catalogError(error: Error): string {
  if (!(error instanceof ApiError)) return 'O catálogo publicado está incompleto.';
  if (error.status === 404) return 'Esta loja não tem um catálogo publicado.';
  if (isOffline(error)) return 'Sem ligação — não foi possível carregar os artigos.';
  return `Não foi possível carregar os artigos. ${errorMessage(error)}`;
}

/** A draft's change refused: a stale version means another terminal changed it first. */
function draftMessage(error: Error): string {
  if (isVersionConflict(error)) {
    return 'A linha foi alterada noutro terminal. O pedido foi atualizado; reveja-o e tente de novo.';
  }
  if (!(error instanceof ApiError)) return 'Não foi possível alterar a linha.';
  if (isOffline(error)) return 'Sem ligação — a alteração da linha não foi confirmada.';
  return `Não foi possível alterar a linha. ${errorMessage(error)}`;
}

function closedMessage(order: Order): string {
  return order.lifecycle === 'CANCELLED' ? 'Este pedido foi cancelado.' : 'Este pedido está fechado.';
}
