import { Button, formatMoney, Input, Label, Money, type MoneyValue } from '@restaurantpro/ui';
import { Banknote } from 'lucide-react';
import { useState } from 'react';
import { type Attempt, useCharge } from '../hooks/use-settlement-commands';
import { amountInput, openAttemptOn, type Settlement, type Target } from '../settlement';
import { CommandStatus } from './command-status';

type Props = {
  settlement: Settlement;
  target: Target;
  /** "Sem ligação" or a closed order: no command. */
  disabled: boolean;
};

/** The one method every company has (D52: `CASH` is seeded); the POS cannot list the others yet (contract gap). */
const CASH = { code: 'CASH', name: 'Numerário', detail: 'Com troco' };

/**
 * Stitch's "Valor a liquidar" and "Forma de pagamento": what the selected target still owes, as the server says, and
 * one charge of it (or of part of it) per press. The change is the server's answer, never a subtraction made here.
 */
export function PaymentPanel({ settlement, target, disabled }: Props) {
  const [last, setLast] = useState<Attempt>();
  const charge = useCharge(settlement.orderId, setLast);
  const inDoubt = openAttemptOn(settlement, target) !== undefined;

  return (
    <section aria-labelledby="pay-title" className="flex flex-col gap-4">
      <div className="flex flex-col gap-2 rounded-xl border bg-card p-4">
        <h3 id="pay-title" className="sr-only">
          Pagamento
        </h3>
        <h4 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">Valor a cobrar — {target.label}</h4>
        <Money value={target.balance} className="text-4xl font-bold lg:text-5xl" />
      </div>
      {inDoubt && (
        <p className="rounded-lg border border-warning p-3 font-medium">
          Há um pagamento em curso nesta conta. Resolva-o antes de cobrar de novo.
        </p>
      )}
      {/* A new target, or a new balance from the server, starts the amounts again. */}
      <ChargeForm
        key={`${target.key}:${target.balance.amount}`}
        due={target.balance}
        disabled={disabled || inDoubt || charge.intent !== undefined}
        onCharge={(amount, tendered) => {
          setLast(undefined);
          charge.start({ orderId: settlement.orderId, partitionId: target.partitionId, methodCode: CASH.code, amount, tendered }, undefined);
        }}
      />
      <div className="min-h-12">
        <CommandStatus
          command={charge}
          pendingText="A cobrar…"
          unconfirmedText="Sem ligação — o pagamento não foi confirmado. Não cobre de novo: tente novamente."
          refusedText="O pagamento não foi feito."
          blocked={disabled}
          discardable={false}
        />
        {last && <LastPayment attempt={last} />}
      </div>
    </section>
  );
}

function ChargeForm({
  due,
  disabled,
  onCharge,
}: {
  due: MoneyValue;
  disabled: boolean;
  onCharge: (amount: string, tendered: string | undefined) => void;
}) {
  const [amountText, setAmountText] = useState(String(due.amount));
  const [tenderedText, setTenderedText] = useState('');
  const amount = amountInput(amountText);
  const tendered = tenderedText.trim() ? amountInput(tenderedText) : undefined;
  const valid = amount !== undefined && (tenderedText.trim() === '' || tendered !== undefined);

  return (
    <form
      className="flex flex-col gap-4 rounded-xl border bg-card p-4"
      onSubmit={(event) => {
        event.preventDefault();
        if (valid && !disabled) onCharge(amount, tendered);
      }}
    >
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">Forma de pagamento</legend>
        <label className="flex min-h-20 cursor-pointer items-center gap-3 rounded-lg border-2 border-primary bg-muted p-3 has-focus-visible:ring-2 has-focus-visible:ring-ring">
          <input type="radio" name="method" value={CASH.code} checked readOnly className="sr-only" />
          <Banknote aria-hidden="true" className="size-6 text-primary" />
          <span className="flex flex-col">
            <span className="font-semibold">{CASH.name}</span>
            <span className="text-sm text-muted-foreground">{CASH.detail}</span>
          </span>
        </label>
      </fieldset>
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="flex flex-col gap-1">
          <Label htmlFor="charge-amount">Valor a pagar</Label>
          <Input
            id="charge-amount"
            inputMode="decimal"
            autoComplete="off"
            value={amountText}
            aria-invalid={amount === undefined}
            onChange={(event) => setAmountText(event.target.value)}
            className="h-12 text-lg tabular-nums"
          />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="charge-tendered">Valor recebido</Label>
          <Input
            id="charge-tendered"
            inputMode="decimal"
            autoComplete="off"
            value={tenderedText}
            aria-invalid={tenderedText.trim() !== '' && tendered === undefined}
            onChange={(event) => setTenderedText(event.target.value)}
            className="h-12 text-lg tabular-nums"
          />
        </div>
      </div>
      <Button type="submit" size="touch-primary" disabled={disabled || !valid}>
        {amount ? `Cobrar ${formatMoney({ amount, currency: due.currency })}` : 'Cobrar'}
      </Button>
    </form>
  );
}

function LastPayment({ attempt }: { attempt: Attempt }) {
  if (attempt.status !== 'SUCCEEDED' || !attempt.amount) return null;
  const method = attempt.methodKind === 'CASH' ? 'numerário' : attempt.methodCode;
  return (
    <p role="status" aria-label="Último pagamento" className="font-medium">
      Pago {formatMoney(attempt.amount)} em {method}.{attempt.change && ` Troco: ${formatMoney(attempt.change)}.`}
    </p>
  );
}
