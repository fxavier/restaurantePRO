import { Button, cn, LOCALE, Money } from '@restaurantpro/ui';
import type { Partition } from '../settlement';

const quantity = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 3 });

type Props = {
  partitions: Partition[];
  /** The part being charged; none: the order's residual. */
  selected: string | undefined;
  onSelect: (partitionId: string | undefined) => void;
};

/** Stitch's "Subcontas": the bill's live parts, each with what it still owes; one is chosen to be charged. */
export function PartitionList({ partitions, selected, onSelect }: Props) {
  return (
    <section aria-labelledby="parts-title" className="flex flex-col gap-2">
      <h3 id="parts-title" className="text-xl font-semibold">
        Divisões ({partitions.length})
      </h3>
      {partitions.length === 0 ? (
        <p className="text-muted-foreground">A conta não está dividida.</p>
      ) : (
        <ul aria-label="Divisões" className="flex flex-col gap-2">
          {partitions.map((partition) => {
            const chosen = partition.id === selected;
            return (
              <li
                key={partition.id}
                className={cn('flex flex-col gap-2 rounded-xl border-2 bg-card p-4', chosen ? 'border-primary' : 'border-border')}
              >
                <div className="flex items-start justify-between gap-3">
                  <span className="font-semibold">{partition.label}</span>
                  <Money value={partition.balance} className="text-xl font-bold" />
                </div>
                {partition.lines.length > 0 && (
                  <p className="text-sm text-muted-foreground">
                    {partition.lines
                      .map((line) => `${quantity.format(line.quantity as Intl.StringNumericLiteral)} × ${line.description}`)
                      .join(', ')}
                  </p>
                )}
                <div className="flex flex-wrap gap-2">
                  <Button
                    variant={chosen ? 'default' : 'outline'}
                    size="touch"
                    aria-pressed={chosen}
                    onClick={() => onSelect(chosen ? undefined : partition.id)}
                  >
                    Cobrar {partition.label}
                  </Button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
