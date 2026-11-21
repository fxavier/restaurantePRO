import { Button, cn, LOCALE, Money, type StatusMap, StatusBadge } from '@restaurantpro/ui';
import { Circle, CircleCheck, CircleDot, FileText, NotebookText, Split, X } from 'lucide-react';
import { useState } from 'react';
import { type Buyer, type FiscalDocument, type Reprint, useCancelPartition, useIssuePartitionDocument } from '../hooks/use-settlement-commands';
import type { Can } from '../hooks/use-permissions';
import { isCancellable, type Partition, type PaymentStatus } from '../settlement';
import { CommandStatus } from './command-status';
import { DocumentNotice, ReprintButton } from './document-actions';

const quantity = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 3 });

/** Stitch's pills: the state by label and icon, never colour alone (§8.4). */
const PAYMENT_STATUS: StatusMap<PaymentStatus> = {
  UNPAID: { label: 'Por pagar', icon: Circle, tone: 'warning' },
  PARTIALLY_PAID: { label: 'Pago em parte', icon: CircleDot, tone: 'info' },
  PAID: { label: 'Pago', icon: CircleCheck, tone: 'success' },
  ON_ACCOUNT: { label: 'A crédito', icon: NotebookText, tone: 'info' },
  MIXED: { label: 'Misto', icon: Split, tone: 'info' },
};

type Props = {
  orderId: string;
  partition: Partition;
  /** Being charged now (Stitch's "Em cobrança"). */
  chosen: boolean;
  onChoose: () => void;
  /** Who a document issued here is for; undefined while a NIF is asked for and still empty. */
  buyer: { ready: boolean; buyer?: Buyer };
  /** "Sem ligação": no command, not even a copy (FC §11). */
  blocked: boolean;
  /** The bill is open: a closed one only reprints. */
  open: boolean;
  can: Can;
};

/**
 * One part of the bill (Stitch's "Subconta"): what it holds and still owes, its payment status, and its commands —
 * charge it, cancel it while untouched, issue its own document (by items, once paid, D70) or a copy of it.
 */
export function PartitionCard({ orderId, partition, chosen, onChoose, buyer, blocked, open, can }: Props) {
  const disabled = blocked || !open;

  return (
    <li className={cn('flex flex-col gap-2 rounded-xl border-2 bg-card p-4', chosen ? 'border-primary' : 'border-border')}>
      <div className="flex items-start justify-between gap-3">
        <span className="flex flex-wrap items-center gap-2">
          <span className="font-semibold">{partition.label}</span>
          <StatusBadge status={partition.paymentStatus} statuses={PAYMENT_STATUS} />
        </span>
        <Money value={partition.settled ? partition.due : partition.balance} className="text-xl font-bold" />
      </div>
      {partition.lines.length > 0 && (
        <p className="text-sm text-muted-foreground">
          {partition.lines.map((line) => `${quantity.format(line.quantity as Intl.StringNumericLiteral)} × ${line.description}`).join(', ')}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        {can.collect && !partition.settled && (
          <Button
            variant={chosen ? 'default' : 'outline'}
            size="touch"
            aria-pressed={chosen}
            aria-label={`Cobrar ${partition.label}`}
            disabled={disabled}
            onClick={onChoose}
          >
            Cobrar
          </Button>
        )}
        <PartitionDocument orderId={orderId} partition={partition} buyer={buyer} blocked={blocked} disabled={disabled} can={can} />
        <CancelPartition
          orderId={orderId}
          partition={partition}
          allowed={can.split && isCancellable(partition)}
          blocked={blocked}
          disabled={disabled}
        />
      </div>
    </li>
  );
}

type CommandProps = { orderId: string; partition: Partition; blocked: boolean; disabled: boolean };

/** A part by items, once paid, issues its own document (D70); once issued, a copy of it. */
function PartitionDocument({ orderId, partition, buyer, blocked, disabled, can }: CommandProps & Pick<Props, 'buyer' | 'can'>) {
  const [issued, setIssued] = useState<FiscalDocument>();
  const [reprint, setReprint] = useState<Reprint>();
  const issue = useIssuePartitionDocument(orderId, (document) => {
    setReprint(undefined);
    setIssued(document);
  });
  const invoiceable = can.collect && partition.kind === 'ITEMS' && partition.settled && !partition.invoiced;
  const reprintable = can.reprint && partition.invoiced && partition.documentId;

  return (
    <>
      {invoiceable && (
        <Button
          variant="outline"
          size="touch"
          disabled={disabled || !buyer.ready || issue.intent !== undefined}
          onClick={() => issue.start({ partitionId: partition.id, buyer: buyer.buyer }, undefined)}
        >
          <FileText aria-hidden="true" />
          Emitir documento de {partition.label}
        </Button>
      )}
      {reprintable && (
        <ReprintButton
          documentId={reprintable}
          name={`de ${partition.label}`}
          disabled={blocked}
          onPrinted={(copy) => {
            setIssued(undefined);
            setReprint(copy);
          }}
        />
      )}
      <div className="w-full empty:hidden">
        <DocumentNotice label={`Documento de ${partition.label}`} issued={issued} reprint={reprint} />
        <CommandStatus
          command={issue}
          pendingText="A emitir o documento…"
          unconfirmedText="Sem ligação — o documento não foi confirmado."
          refusedText="O documento não foi emitido."
          blocked={blocked}
        />
      </div>
    </>
  );
}

/**
 * Cancel a part nothing has touched yet (D67), at its version. Mounted even when not allowed, so a refusal (the part
 * was paid meanwhile) stays on screen after the button goes.
 */
function CancelPartition({ orderId, partition, allowed, blocked, disabled }: CommandProps & { allowed: boolean }) {
  const cancel = useCancelPartition(orderId);
  return (
    <>
      {allowed && (
        <Button
          variant="ghost"
          size="touch"
          aria-label={`Cancelar ${partition.label}`}
          disabled={disabled || cancel.intent !== undefined}
          onClick={() => cancel.start({ partitionId: partition.id, version: partition.version }, undefined)}
        >
          <X aria-hidden="true" />
          Cancelar
        </Button>
      )}
      <div className="w-full empty:hidden">
        <CommandStatus
          command={cancel}
          pendingText="A cancelar a divisão…"
          unconfirmedText="Sem ligação — o cancelamento não foi confirmado."
          refusedText="A divisão não foi cancelada."
          blocked={blocked}
        />
      </div>
    </>
  );
}
