import { Button, cn, LOCALE, Money, type StatusMap, StatusBadge } from '@restaurantpro/ui';
import { Circle, CircleCheck, CircleDot, NotebookText, Split, X } from 'lucide-react';
import { useCancelPartition } from '../hooks/use-settlement-commands';
import { isCancellable, type Partition, type PaymentStatus } from '../settlement';
import { CommandStatus } from './command-status';

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
  partitions: Partition[];
  /** The part being charged; none: the order's residual. */
  selected: string | undefined;
  onSelect: (partitionId: string | undefined) => void;
  /** "Sem ligação" or a closed order: no command. */
  disabled: boolean;
};

/** Stitch's "Subcontas": the bill's live parts, each with what it still owes; one is chosen to be charged. */
export function PartitionList({ orderId, partitions, selected, onSelect, disabled }: Props) {
  const cancel = useCancelPartition(orderId);
  return (
    <section aria-labelledby="parts-title" className="flex flex-col gap-2">
      <h3 id="parts-title" className="text-xl font-semibold">
        Divisões ({partitions.length})
      </h3>
      <CommandStatus
        command={cancel}
        pendingText="A cancelar a divisão…"
        unconfirmedText="Sem ligação — o cancelamento não foi confirmado."
        refusedText="A divisão não foi cancelada."
        blocked={disabled}
      />
      {partitions.length === 0 ? (
        <p className="text-muted-foreground">A conta não está dividida.</p>
      ) : (
        <ul aria-label="Divisões" className="flex flex-col gap-2">
          {partitions.map((partition) => {
            const chosen = partition.id === selected;
            return (
              <li
                key={partition.id}
                className={cn(
                  'flex flex-col gap-2 rounded-xl border-2 bg-card p-4',
                  chosen ? 'border-primary' : partition.settled ? 'border-success/50' : 'border-border',
                )}
              >
                <div className="flex items-start justify-between gap-3">
                  <span className="flex flex-wrap items-center gap-2">
                    <span className="font-semibold">{partition.label}</span>
                    <StatusBadge status={partition.paymentStatus} statuses={PAYMENT_STATUS} />
                  </span>
                  <Money value={partition.settled ? partition.due : partition.balance} className="text-xl font-bold" />
                </div>
                {partition.lines.length > 0 && (
                  <p className="text-sm text-muted-foreground">
                    {partition.lines
                      .map((line) => `${quantity.format(line.quantity as Intl.StringNumericLiteral)} × ${line.description}`)
                      .join(', ')}
                  </p>
                )}
                <div className="flex flex-wrap gap-2">
                  {!partition.settled && (
                    <Button
                      variant={chosen ? 'default' : 'outline'}
                      size="touch"
                      aria-pressed={chosen}
                      disabled={disabled}
                      onClick={() => onSelect(chosen ? undefined : partition.id)}
                    >
                      Cobrar {partition.label}
                    </Button>
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
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
