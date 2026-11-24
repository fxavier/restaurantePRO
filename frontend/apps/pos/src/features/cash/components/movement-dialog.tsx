import { ApiError } from '@restaurantpro/api-client';
import {
  Button,
  ConfirmButton,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
} from '@restaurantpro/ui';
import { type FormEvent, useId, useState } from 'react';
import { useCommandsBlocked } from '../../../lib/live/use-connection';
import { MOVEMENTS, type MovementType, movementRequest, type TypedMovement } from '../cash';
import { type MovementView, useRecordMovement } from '../hooks/use-cash-commands';
import { cashRefusal } from '../messages';
import { AmountField } from './amount-field';
import { CommandFeedback } from './command-feedback';

type Props = {
  type: MovementType;
  sessionId: string;
  /** The session's currency, which labels the amount. */
  currency: string | undefined;
  onClose: () => void;
  onRecorded: (movement: MovementView) => void;
};

const DESCRIPTIONS: Record<MovementType, string> = {
  CASH_IN: 'Dinheiro que entra na gaveta sem ser de uma venda (por exemplo, trocos).',
  CASH_OUT: 'Dinheiro retirado da gaveta (por exemplo, para o cofre).',
  EXPENSE: 'Um pagamento em numerário a partir da gaveta, aprovado por outro empregado com o seu código e PIN.',
};

/**
 * A cash movement by hand (§4): its amount and reason, and for an expense another employee's code and PIN (P24). It
 * closes once the server has recorded it.
 */
export function MovementDialog({ type, sessionId, currency, onClose, onRecorded }: Props) {
  const [typed, setTyped] = useState<TypedMovement>({ type, amount: '', reason: '', approverCode: '', approverPin: '' });
  const set = (field: Exclude<keyof TypedMovement, 'type'>) => (text: string) =>
    setTyped((current) => ({ ...current, [field]: text }));
  const expense = type === 'EXPENSE';
  const record = useRecordMovement(
    () => (expense ? { code: typed.approverCode, pin: typed.approverPin } : undefined),
    onRecorded,
  );
  const blocked = useCommandsBlocked();
  const request = movementRequest(sessionId, typed);
  const awaitingRetry = !record.pending && record.intent !== undefined;
  const locked = record.pending || awaitingRetry;

  function submit(event: FormEvent) {
    event.preventDefault();
    if (blocked || !request) return;
    if (awaitingRetry) record.retry();
    else record.start(request, undefined);
  }

  return (
    // With no answer the server may already hold the movement: the dialog keeps its intent (and key) until a retry
    // gets a definitive answer, so a second "Registar" can never record it twice (FC §8).
    <Dialog open onOpenChange={(open) => !open && !locked && onClose()}>
      <DialogContent showCloseButton={false} className="sm:max-w-lg">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle className="text-xl">{MOVEMENTS[type].action}</DialogTitle>
            <DialogDescription>{DESCRIPTIONS[type]}</DialogDescription>
          </DialogHeader>
          <AmountField label="Valor" currency={currency} value={typed.amount} onChange={set('amount')} readOnly={locked} />
          <TextField label="Motivo" value={typed.reason} onChange={set('reason')} readOnly={locked} maxLength={500} />
          {expense && (
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <TextField label="Código do aprovador" value={typed.approverCode} onChange={set('approverCode')} readOnly={locked} numeric />
              <TextField label="PIN do aprovador" value={typed.approverPin} onChange={set('approverPin')} readOnly={locked} numeric secret />
            </div>
          )}
          <CommandFeedback blocked={blocked} error={record.error && refusal(record.error, expense)} />
          <DialogFooter>
            <Button type="button" variant="outline" size="touch" onClick={onClose} disabled={locked}>
              Cancelar
            </Button>
            <ConfirmButton type="submit" size="touch-primary" pending={record.pending} disabled={blocked || !request}>
              {awaitingRetry ? 'Tentar novamente' : 'Registar'}
            </ConfirmButton>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function refusal(error: Error, expense: boolean): string {
  if (expense && error instanceof ApiError && error.status === 403) {
    return 'Aprovação recusada: confirme o código e o PIN do aprovador, que tem de poder autorizar despesas.';
  }
  return cashRefusal(error, 'o movimento');
}

type TextFieldProps = {
  label: string;
  value: string;
  onChange: (value: string) => void;
  readOnly: boolean;
  maxLength?: number;
  /** Digits only, on a numeric keyboard. */
  numeric?: boolean;
  /** A PIN: masked, never autofilled. */
  secret?: boolean;
};

function TextField({ label, value, onChange, readOnly, maxLength, numeric, secret }: TextFieldProps) {
  const id = useId();
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id} className="text-base">
        {label}
      </Label>
      <Input
        id={id}
        type={secret ? 'password' : 'text'}
        inputMode={numeric ? 'numeric' : undefined}
        autoComplete="off"
        maxLength={maxLength}
        value={value}
        readOnly={readOnly}
        onChange={(event) => onChange(numeric ? event.target.value.replace(/\D/g, '') : event.target.value)}
        className="h-12 text-base md:text-base"
      />
    </div>
  );
}
