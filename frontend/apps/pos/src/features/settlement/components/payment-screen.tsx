import { ApiError } from '@restaurantpro/api-client';
import { Button, cn, Money, type MoneyValue, Skeleton } from '@restaurantpro/ui';
import { ArrowLeft, ClipboardList, ReceiptText } from 'lucide-react';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { errorMessage, isOffline } from '../../../lib/error-messages';
import { useSettlementPermissions } from '../hooks/use-permissions';
import { useOrderHeading, useSettlement } from '../hooks/use-settlement';
import { SETTLEMENT_MESSAGES } from '../messages';
import { buyerOf, FINAL_CONSUMER, type Settlement, targetOf } from '../settlement';
import { BuyerFields } from './buyer-fields';
import { ClosingPanel } from './closing-panel';
import { OpenAttempts } from './open-attempts';
import { PartitionList } from './partition-list';
import { PaymentPanel } from './payment-panel';
import { SplitPanel } from './split-panel';

type Props = {
  orderId: string;
  /** "Sem ligação": every command is disabled (FC §11). */
  blocked: boolean;
  /** Opened to split the bill (the table map's "Dividir"): the split starts on equal parts. */
  splitFirst?: boolean;
};

/**
 * "Conta & Pagamento" (Stitch; §4, §8.5): one order's bill — its split (left), the charge of the chosen part (centre),
 * and its document and close (right). Every amount and state is the server's, read again after each command.
 */
export function PaymentScreen({ orderId, blocked, splitFirst = false }: Props) {
  const settlement = useSettlement(orderId);
  const heading = useOrderHeading(orderId);
  const navigate = useNavigate();
  const tableCode = heading.data?.tableCode;
  const meta = [heading.data?.orderNumber !== undefined && `Pedido n.º ${heading.data.orderNumber}`, guestsText(heading.data?.guests)]
    .filter(Boolean)
    .join(' · ');

  return (
    <main className="flex min-h-0 flex-1 flex-col gap-3 p-4">
      {/* Light reference: the bill's heading card, as /tables' status bar, then the bill's cards below it. */}
      <header className="flex flex-wrap items-center gap-3 rounded-2xl border bg-card px-4 py-3">
        <span
          aria-hidden="true"
          className="flex size-12 shrink-0 items-center justify-center rounded-xl border-2 border-info-border bg-info-tint text-info-text"
        >
          <ReceiptText className="size-6" />
        </span>
        <div className="min-w-0 flex-1">
          {/* The page's eyebrow: the section bar has no tab for it, since it is one order's page. */}
          <h2 className="text-sm font-medium text-muted-foreground">Conta & Pagamento</h2>
          <h3 className="truncate text-xl font-semibold">{tableCode ? `Conta da mesa ${tableCode}` : 'Conta do pedido'}</h3>
          <p className="text-sm text-muted-foreground">{meta}</p>
        </div>
        {settlement.data && <Summary settlement={settlement.data} />}
      </header>
      {settlement.data ? (
        <Bill
          settlement={settlement.data}
          guests={heading.data?.guests}
          blocked={blocked}
          splitFirst={splitFirst}
          onClosed={() => void navigate('/tables')}
        />
      ) : settlement.isError ? (
        <div className="flex flex-col items-center gap-3 rounded-2xl border bg-card p-6 text-center">
          <p role="alert">{loadError(settlement.error)}</p>
          {!(settlement.error instanceof ApiError && settlement.error.status === 404) && (
            <Button variant="outline" size="touch" className="rounded-xl bg-card" onClick={() => void settlement.refetch()}>
              Tentar novamente
            </Button>
          )}
        </div>
      ) : (
        <div role="status" aria-label="A carregar a conta…" className="grid gap-3 lg:grid-cols-3">
          <Skeleton className="h-96 rounded-2xl" />
          <Skeleton className="h-96 rounded-2xl" />
          <Skeleton className="h-96 rounded-2xl" />
        </div>
      )}
      <nav aria-label="Navegação da conta" className="flex flex-wrap gap-2">
        <Button asChild variant="outline" size="touch" className="rounded-xl bg-card">
          <Link to={`/orders/${orderId}`}>
            <ClipboardList aria-hidden="true" />
            Voltar ao pedido
          </Link>
        </Button>
        <Button asChild variant="ghost" size="touch" className="rounded-xl">
          <Link to="/tables">
            <ArrowLeft aria-hidden="true" />
            Voltar às mesas
          </Link>
        </Button>
      </nav>
    </main>
  );
}

function Bill({
  settlement,
  guests,
  blocked,
  splitFirst,
  onClosed,
}: {
  settlement: Settlement;
  guests: number | null | undefined;
  blocked: boolean;
  splitFirst: boolean;
  onClosed: () => void;
}) {
  const [selected, setSelected] = useState<string>();
  const [buyer, setBuyer] = useState(FINAL_CONSUMER);
  const open = settlement.lifecycle === 'OPEN';
  // A closed or cancelled bill takes no command, nor does any while "Sem ligação" (FC §11).
  const disabled = blocked || !open;
  const documentBuyer = buyerOf(buyer);
  const can = useSettlementPermissions();
  // What is charged, resolved against the live parts: a chosen part cancelled here or elsewhere gives way to the
  // residual, and the list shows the same choice the payment panel charges.
  const target = targetOf(settlement, selected);

  return (
    <>
      {!open && (
        <p role="alert" className="rounded-2xl border bg-card p-3 font-medium">
          {settlement.lifecycle === 'CANCELLED' ? 'Este pedido foi cancelado.' : 'Esta conta está fechada.'}
        </p>
      )}
      {!can.split && !can.collect && (
        <p className="rounded-2xl border bg-card p-3 font-medium">Sem permissão para dividir ou cobrar esta conta.</p>
      )}
      <div className="grid items-start gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <div className="flex flex-col gap-3">
          {can.split && <SplitPanel settlement={settlement} guests={guests} disabled={disabled} mode={splitFirst ? 'equal' : 'whole'} />}
          <PartitionList
            orderId={settlement.orderId}
            partitions={settlement.partitions}
            selected={target.partitionId}
            residual={settlement.residualBalance}
            onSelect={setSelected}
            buyer={documentBuyer}
            blocked={blocked}
            open={open}
            can={can}
          />
        </div>
        <div className="flex flex-col gap-3">
          {can.collect && <PaymentPanel settlement={settlement} target={target} disabled={disabled} />}
          <OpenAttempts
            orderId={settlement.orderId}
            attempts={settlement.openAttempts}
            targetLabel={(attempt) => targetOf(settlement, attempt.partitionId).label}
            disabled={disabled}
            canResolve={can.collect}
          />
        </div>
        <div className="flex flex-col gap-3 sm:col-span-2 lg:col-span-1">
          <ClosingPanel settlement={settlement} buyer={documentBuyer} blocked={blocked} onClosed={onClosed} can={can}>
            <BuyerFields value={buyer} onChange={setBuyer} />
          </ClosingPanel>
        </div>
      </div>
    </>
  );
}

/**
 * The three figures: the total, what is paid, and what is still outstanding, as the server has them. Light reference:
 * the state tints of /tables' chips (paid green, outstanding amber), each also named by its term.
 */
function Summary({ settlement }: { settlement: Settlement }) {
  return (
    // The group on a wrapper: a role on the <dl> itself would orphan its terms (axe dlitem).
    <div role="group" aria-label="Resumo da conta">
      <dl className="flex flex-wrap gap-2">
        <Stat label="Total da conta" value={settlement.due} className="bg-muted" />
        <Stat label="Pago" value={settlement.paid} className="bg-success-tint text-success-text" />
        <Stat label="Saldo pendente" value={settlement.balance} className="bg-warning-tint text-warning-text" />
      </dl>
    </div>
  );
}

function Stat({ label, value, className }: { label: string; value: MoneyValue; className: string }) {
  return (
    <div className={cn('flex min-w-32 flex-col rounded-xl px-3 py-1.5', className)}>
      <dt className="text-sm">{label}</dt>
      <dd>
        <Money value={value} className="text-xl font-bold" />
      </dd>
    </div>
  );
}

function guestsText(guests: number | null | undefined): string | undefined {
  if (guests === null || guests === undefined) return undefined;
  return guests === 1 ? '1 pessoa' : `${guests} pessoas`;
}

function loadError(error: Error): string {
  if (!(error instanceof ApiError)) return 'Não foi possível carregar a conta. A resposta do servidor está incompleta.';
  if (error.status === 404) return 'Pedido não encontrado.';
  if (isOffline(error)) return 'Sem ligação — não foi possível carregar a conta.';
  return `Não foi possível carregar a conta. ${errorMessage(error, { messages: SETTLEMENT_MESSAGES })}`;
}
