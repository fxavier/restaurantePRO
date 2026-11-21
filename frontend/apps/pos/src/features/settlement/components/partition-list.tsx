import { Button, Money, type MoneyValue } from '@restaurantpro/ui';
import type { Can } from '../hooks/use-permissions';
import type { Buyer } from '../hooks/use-settlement-commands';
import type { Partition } from '../settlement';
import { PartitionCard } from './partition-card';

type Props = {
  orderId: string;
  partitions: Partition[];
  /** The part being charged; none: the order's residual. */
  selected: string | undefined;
  /** What the order still owes outside its parts, as the server says. */
  residual: MoneyValue;
  onSelect: (partitionId: string | undefined) => void;
  buyer: { ready: boolean; buyer?: Buyer };
  /** "Sem ligação": no command (FC §11). */
  blocked: boolean;
  /** The bill is open: a closed one only reprints. */
  open: boolean;
  can: Can;
};

/** Stitch's "Subcontas": the bill's live parts; one is chosen to be charged. */
export function PartitionList({ orderId, partitions, selected, residual, onSelect, buyer, blocked, open, can }: Props) {
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
              blocked={blocked}
              open={open}
              can={can}
            />
          ))}
        </ul>
      )}
      {/* The way back to the order's residual: a paid part shows no "Cobrar" of its own to unchoose it. */}
      {partitions.length > 0 && can.collect && (
        <Button
          variant={selected === undefined ? 'default' : 'outline'}
          size="touch"
          aria-pressed={selected === undefined}
          disabled={blocked || !open}
          onClick={() => onSelect(undefined)}
          className="justify-between"
        >
          <span>
            <span className="sr-only">Cobrar</span> Restante da conta
          </span>{' '}
          <Money value={residual} />
        </Button>
      )}
    </section>
  );
}
