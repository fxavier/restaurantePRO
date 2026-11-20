import type { Buyer } from '../hooks/use-settlement-commands';
import type { Partition } from '../settlement';
import { PartitionCard } from './partition-card';

type Props = {
  orderId: string;
  partitions: Partition[];
  /** The part being charged; none: the order's residual. */
  selected: string | undefined;
  onSelect: (partitionId: string | undefined) => void;
  buyer: { ready: boolean; buyer?: Buyer };
  /** "Sem ligação" or a closed order: no command. */
  disabled: boolean;
};

/** Stitch's "Subcontas": the bill's live parts; one is chosen to be charged. */
export function PartitionList({ orderId, partitions, selected, onSelect, buyer, disabled }: Props) {
  return (
    <section aria-labelledby="parts-title" className="flex flex-col gap-2">
      <h3 id="parts-title" className="text-xl font-semibold">
        Divisões ({partitions.length})
      </h3>
      {partitions.length === 0 ? (
        <p className="text-muted-foreground">A conta não está dividida.</p>
      ) : (
        <ul aria-label="Divisões" className="flex flex-col gap-2">
          {partitions.map((partition) => (
            <PartitionCard
              key={partition.id}
              orderId={orderId}
              partition={partition}
              chosen={partition.id === selected}
              onChoose={() => onSelect(partition.id === selected ? undefined : partition.id)}
              buyer={buyer}
              disabled={disabled}
            />
          ))}
        </ul>
      )}
    </section>
  );
}
