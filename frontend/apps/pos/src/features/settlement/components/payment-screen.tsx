import { ApiError } from '@restaurantpro/api-client';
import { Button, Money, type MoneyValue, Skeleton } from '@restaurantpro/ui';
import { errorMessage, isOffline } from '../../../lib/error-messages';
import { useOrderHeading, useSettlement } from '../hooks/use-settlement';
import { useState } from 'react';
import { SETTLEMENT_MESSAGES } from '../messages';
import { targetOf } from '../settlement';
import { OpenAttempts } from './open-attempts';
import { PartitionList } from './partition-list';
import { PaymentPanel } from './payment-panel';
import { SplitPanel } from './split-panel';

type Props = {
  orderId: string;
  /** "Sem ligação": every command is disabled (FC §11). */
  blocked: boolean;
};

/** "Conta & Pagamento" (Stitch; §4, §8.5): one order's bill, its split, its payments, its document and its close. */
export function PaymentScreen({ orderId, blocked }: Props) {
  const settlement = useSettlement(orderId);
  const heading = useOrderHeading(orderId);
  const tableCode = heading.data?.tableCode;
  const [selected, setSelected] = useState<string>();

  return (
    <main className="flex min-h-0 flex-1 flex-col gap-4 p-4">
      <h2 className="text-2xl font-semibold">{tableCode ? `Conta da mesa ${tableCode}` : 'Conta do pedido'}</h2>
      {settlement.data ? (
        <>
          <dl role="group" aria-label="Resumo da conta" className="flex gap-4">
            <Stat label="Total da conta" value={settlement.data.due} />
            <Stat label="Pago" value={settlement.data.paid} />
            <Stat label="Saldo pendente" value={settlement.data.balance} />
          </dl>
          <SplitPanel settlement={settlement.data} guests={heading.data?.guests} disabled={blocked} />
          <PartitionList partitions={settlement.data.partitions} selected={selected} onSelect={setSelected} />
          <PaymentPanel settlement={settlement.data} target={targetOf(settlement.data, selected)} disabled={blocked} />
          <OpenAttempts
            orderId={orderId}
            attempts={settlement.data.openAttempts}
            targetLabel={(attempt) => targetOf(settlement.data, attempt.partitionId).label}
            disabled={blocked}
          />
        </>
      ) : settlement.isError ? (
        <div className="flex flex-col items-center gap-3 p-6 text-center">
          <p role="alert">{loadError(settlement.error)}</p>
          {!(settlement.error instanceof ApiError && settlement.error.status === 404) && (
            <Button variant="outline" size="touch" onClick={() => void settlement.refetch()}>
              Tentar novamente
            </Button>
          )}
        </div>
      ) : (
        <div role="status" aria-label="A carregar a conta…" className="grid gap-4 lg:grid-cols-3">
          <Skeleton className="h-64" />
          <Skeleton className="h-64" />
          <Skeleton className="h-64" />
        </div>
      )}
    </main>
  );
}

function Stat({ label, value }: { label: string; value: MoneyValue }) {
  return (
    <div className="flex flex-col">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd>
        <Money value={value} className="text-2xl font-bold" />
      </dd>
    </div>
  );
}

function loadError(error: Error): string {
  if (!(error instanceof ApiError)) return 'Não foi possível carregar a conta. A resposta do servidor está incompleta.';
  if (error.status === 404) return 'Pedido não encontrado.';
  if (isOffline(error)) return 'Sem ligação — não foi possível carregar a conta.';
  return `Não foi possível carregar a conta. ${errorMessage(error, { messages: SETTLEMENT_MESSAGES })}`;
}
