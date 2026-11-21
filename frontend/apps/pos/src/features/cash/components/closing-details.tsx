import {
  ConfirmButton,
  Input,
  Label,
  Money,
  StatusBadge,
  type StatusMap,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { CircleCheck, CircleX, FilePen, Send, TriangleAlert } from 'lucide-react';
import { useId, useState } from 'react';
import { useCommandsBlocked } from '../../../lib/live/use-connection';
import { type ClosingLine, closingLines, fullyJustified, isZero, linesWithDifference, methodLabel } from '../cash';
import { type ClosingView, useClosingSteps } from '../hooks/use-cash-commands';
import { cashRefusal } from '../messages';
import { CommandFeedback } from './command-feedback';

type ClosingStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED';

const STATUSES: StatusMap<ClosingStatus> = {
  DRAFT: { label: 'Rascunho', icon: FilePen, tone: 'neutral' },
  SUBMITTED: { label: 'Submetido', icon: Send, tone: 'info' },
  APPROVED: { label: 'Aprovado', icon: CircleCheck, tone: 'success' },
  REJECTED: { label: 'Rejeitado', icon: CircleX, tone: 'destructive' },
};

type Props = { closing: ClosingView & { id: string }; onClosing: (closing: ClosingView) => void };

/**
 * A drafted closing (§8.3): the server's expected, counted and difference per method; each difference justified,
 * then "Submeter fecho" hands it to the manager for approval.
 */
export function ClosingDetails({ closing, onClosing }: Props) {
  const lines = closingLines(closing.lines);
  const status = closing.status && closing.status in STATUSES ? (closing.status as ClosingStatus) : undefined;

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-2">
        <span className="text-muted-foreground">Estado do fecho:</span>
        {status ? <StatusBadge status={status} statuses={STATUSES} /> : <span>{closing.status}</span>}
      </div>
      <ClosingTable lines={lines} />
      {closing.status === 'DRAFT' ? (
        <DraftActions closing={closing} lines={lines} onClosing={onClosing} />
      ) : (
        <ClosingOutcome closing={closing} />
      )}
    </div>
  );
}

/** A submitted closing waits for the manager; a rejected one says why. */
function ClosingOutcome({ closing }: { closing: ClosingView }) {
  if (closing.status === 'SUBMITTED') return <p className="font-medium">O fecho foi submetido para aprovação do gerente.</p>;
  if (!closing.rejectionReason) return null;
  return <p className="font-medium">Motivo da rejeição: {closing.rejectionReason}</p>;
}

/** A draft's steps: justify its differences, then submit it. */
function DraftActions({ closing, lines, onClosing }: Props & { lines: ClosingLine[] }) {
  const { justify, submit } = useClosingSteps(closing.id, onClosing);
  const blocked = useCommandsBlocked();
  const differences = linesWithDifference(lines);
  const error = justify.error ?? submit.error;
  const awaitingSubmit = !submit.pending && submit.intent !== undefined;

  return (
    <>
      {differences.length > 0 && (
        <Justifications
          differences={differences}
          pending={justify.pending}
          awaitingRetry={!justify.pending && justify.intent !== undefined}
          disabled={blocked || submit.pending}
          onSave={(justifications) =>
            justify.intent ? justify.retry() : justify.start({ justifications, version: closing.version ?? 0 }, undefined)
          }
        />
      )}
      <CommandFeedback blocked={blocked} error={error && cashRefusal(error, 'o fecho da caixa')} />
      <ConfirmButton
        type="button"
        size="touch-primary"
        pending={submit.pending}
        disabled={blocked || !fullyJustified(lines) || justify.pending}
        onClick={() => (awaitingSubmit ? submit.retry() : submit.start(undefined, undefined))}
      >
        <Send aria-hidden="true" />
        {awaitingSubmit ? 'Tentar novamente' : 'Submeter fecho'}
      </ConfirmButton>
    </>
  );
}

/** The server's amounts per method; a difference is marked with an icon and its sign, never by colour alone. */
function ClosingTable({ lines }: { lines: ClosingLine[] }) {
  return (
    <Table>
      <TableCaption className="sr-only">Diferenças por meio de pagamento</TableCaption>
      <TableHeader>
        <TableRow>
          <TableHead>Meio</TableHead>
          <TableHead className="text-right">Esperado</TableHead>
          <TableHead className="text-right">Contado</TableHead>
          <TableHead className="text-right">Diferença</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {lines.map((line) => (
          <TableRow key={line.methodCode}>
            <TableCell className="font-semibold">{methodLabel(line.methodCode)}</TableCell>
            <TableCell className="text-right">{line.expected && <Money value={line.expected} />}</TableCell>
            <TableCell className="text-right">{line.counted && <Money value={line.counted} />}</TableCell>
            <TableCell className="text-right">
              <Difference amount={line.difference} />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

function Difference({ amount }: { amount: ClosingLine['difference'] }) {
  if (amount && !isZero(amount.amount)) {
    return (
      <span className="inline-flex items-center gap-1 font-semibold text-error">
        <TriangleAlert aria-hidden="true" className="size-4" />
        <Money value={amount} />
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1 text-primary-hover">
      <CircleCheck aria-hidden="true" className="size-4" />
      Sem diferença
    </span>
  );
}

type JustificationsProps = {
  differences: ClosingLine[];
  pending: boolean;
  awaitingRetry: boolean;
  disabled: boolean;
  onSave: (justifications: { methodCode: string; text: string }[]) => void;
};

/** One text per difference, prefilled with what the server holds, saved together. */
function Justifications({ differences, pending, awaitingRetry, disabled, onSave }: JustificationsProps) {
  const [texts, setTexts] = useState(() => differences.map((line) => line.justification ?? ''));
  const typedAll = texts.every((text) => text.trim() !== '');
  return (
    <div className="flex flex-col gap-3">
      {differences.map((line, index) => (
        <JustificationField
          key={line.methodCode}
          method={methodLabel(line.methodCode)}
          value={texts[index] ?? ''}
          onChange={(text) => setTexts((current) => current.map((old, at) => (at === index ? text : old)))}
          readOnly={pending || awaitingRetry}
        />
      ))}
      <ConfirmButton
        type="button"
        variant="outline"
        size="touch"
        pending={pending}
        disabled={disabled || !typedAll}
        onClick={() => onSave(differences.map((line, index) => ({ methodCode: line.methodCode, text: (texts[index] ?? '').trim() })))}
      >
        {awaitingRetry ? 'Tentar novamente' : 'Guardar justificações'}
      </ConfirmButton>
    </div>
  );
}

type FieldProps = { method: string; value: string; onChange: (value: string) => void; readOnly: boolean };

function JustificationField({ method, value, onChange, readOnly }: FieldProps) {
  const id = useId();
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id} className="text-base">
        Justificação: {method}
      </Label>
      <Input
        id={id}
        value={value}
        maxLength={500}
        readOnly={readOnly}
        onChange={(event) => onChange(event.target.value)}
        className="h-12 text-base md:text-base"
      />
    </div>
  );
}
