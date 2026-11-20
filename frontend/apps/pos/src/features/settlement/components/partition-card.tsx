import { Button, cn, LOCALE, Money, type StatusMap, StatusBadge } from '@restaurantpro/ui';
import { Circle, CircleCheck, CircleDot, FileText, NotebookText, Split, X } from 'lucide-react';
import { useState } from 'react';
import { type Buyer, type FiscalDocument, type Reprint, useCancelPartition, useIssuePartitionDocument } from '../hooks/use-settlement-commands';
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
  disabled: boolean;
};

/**
 * One part of the bill (Stitch's "Subconta"): what it holds and still owes, its payment status, and its commands —
 * charge it, cancel it while untouched, issue its own document (by items, once paid, D70) or a copy of it.
 */
export function PartitionCard({ orderId, partition, chosen, onChoose, buyer, disabled }: Props) {
  const cancel = useCancelPartition(orderId);
  const [issued, setIssued] = useState<FiscalDocument>();
  const [reprint, setReprint] = useState<Reprint>();
  const issue = useIssuePartitionDocument(orderId, (document) => {
    setReprint(undefined);
    setIssued(document);
  });
  const invoiceable = partition.kind === 'ITEMS' && partition.settled && !partition.invoiced;

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
        {!partition.settled && (
          <Button variant={chosen ? 'default' : 'outline'} size="touch" aria-pressed={chosen} disabled={disabled} onClick={onChoose}>
            Cobrar {partition.label}
          </Button>
        )}
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
        {partition.invoiced && partition.documentId && (
          <ReprintButton
            documentId={partition.documentId}
            name={`de ${partition.label}`}
            disabled={disabled}
            onPrinted={(copy) => {
              setIssued(undefined);
              setReprint(copy);
            }}
          />
        )}
        {isCancellable(partition) && (
          <Button
            variant="ghost"
            size="touch"
            className="ml-auto"
            disabled={disabled || cancel.intent !== undefined}
            onClick={() => cancel.start({ partitionId: partition.id, version: partition.version }, undefined)}
          >
            <X aria-hidden="true" />
            Cancelar {partition.label}
          </Button>
        )}
      </div>
      <DocumentNotice label={`Documento de ${partition.label}`} issued={issued} reprint={reprint} />
      <CommandStatus
        command={issue}
        pendingText="A emitir o documento…"
        unconfirmedText="Sem ligação — o documento não foi confirmado."
        refusedText="O documento não foi emitido."
        blocked={disabled}
      />
      <CommandStatus
        command={cancel}
        pendingText="A cancelar a divisão…"
        unconfirmedText="Sem ligação — o cancelamento não foi confirmado."
        refusedText="A divisão não foi cancelada."
        blocked={disabled}
      />
    </li>
  );
}
