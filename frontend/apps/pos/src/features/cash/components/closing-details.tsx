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
import { fullyJustified, isZero, linesWithDifference, methodLabel } from '../cash';
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
  const { justify, submit } = useClosingSteps(closing.id, onClosing);
  const blocked = useCommandsBlocked();
  const differences = linesWithDifference(closing.lines);
  const [texts, setTexts] = useState<Record<string, string>>(() =>
    Object.fromEntries(differences.map((line) => [line.methodCode ?? '', line.justification ?? ''])),
  );
  const draft = closing.status === 'DRAFT';
  const status = closing.status && closing.status in STATUSES ? (closing.status as ClosingStatus) : undefined;
  const busy = justify.pending || submit.pending;
  const typedAll = differences.every((line) => !!texts[line.methodCode ?? '']?.trim());
  const error = justify.error ?? submit.error;

  function saveJustifications() {
    const justifications = differences.map((line) => ({
      methodCode: line.methodCode ?? '',
      text: (texts[line.methodCode ?? ''] ?? '').trim(),
    }));
    if (justify.intent) justify.retry();
    else justify.start({ justifications, version: closing.version ?? 0 }, undefined);
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-2">
        <span className="text-muted-foreground">Estado do fecho:</span>
        {status ? <StatusBadge status={status} statuses={STATUSES} /> : <span>{closing.status}</span>}
      </div>
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
          {(closing.lines ?? []).map((line) => (
            <TableRow key={line.methodCode}>
              <TableCell className="font-semibold">{methodLabel(line.methodCode ?? '')}</TableCell>
              <TableCell className="text-right">{line.expected && <Money value={line.expected} />}</TableCell>
              <TableCell className="text-right">{line.counted && <Money value={line.counted} />}</TableCell>
              <TableCell className="text-right">
                {line.difference && !isZero(line.difference.amount) ? (
                  <span className="inline-flex items-center gap-1 font-semibold text-error">
                    <TriangleAlert aria-hidden="true" className="size-4" />
                    <Money value={line.difference} />
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-1 text-primary-hover">
                    <CircleCheck aria-hidden="true" className="size-4" />
                    Sem diferença
                  </span>
                )}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {draft && differences.length > 0 && (
        <div className="flex flex-col gap-3">
          {differences.map((line) => (
            <Justification
              key={line.methodCode}
              method={methodLabel(line.methodCode ?? '')}
              value={texts[line.methodCode ?? ''] ?? ''}
              onChange={(text) => setTexts((current) => ({ ...current, [line.methodCode ?? '']: text }))}
              readOnly={busy}
            />
          ))}
          <ConfirmButton
            type="button"
            variant="outline"
            size="touch"
            pending={justify.pending}
            disabled={blocked || !typedAll || submit.pending}
            onClick={saveJustifications}
          >
            {justify.intent && !justify.pending ? 'Tentar novamente' : 'Guardar justificações'}
          </ConfirmButton>
        </div>
      )}
      {draft ? (
        <>
          <CommandFeedback blocked={blocked} error={error && cashRefusal(error, 'o fecho da caixa')} />
          <ConfirmButton
            type="button"
            size="touch-primary"
            pending={submit.pending}
            disabled={blocked || !fullyJustified(closing.lines) || justify.pending}
            onClick={() => (submit.intent ? submit.retry() : submit.start(undefined, undefined))}
          >
            <Send aria-hidden="true" />
            {submit.intent && !submit.pending ? 'Tentar novamente' : 'Submeter fecho'}
          </ConfirmButton>
        </>
      ) : closing.status === 'SUBMITTED' ? (
        <p className="font-medium">O fecho foi submetido para aprovação do gerente.</p>
      ) : closing.rejectionReason ? (
        <p className="font-medium">Motivo da rejeição: {closing.rejectionReason}</p>
      ) : null}
    </div>
  );
}

function Justification({
  method,
  value,
  onChange,
  readOnly,
}: {
  method: string;
  value: string;
  onChange: (value: string) => void;
  readOnly: boolean;
}) {
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
