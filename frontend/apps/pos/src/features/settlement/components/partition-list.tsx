import { LOCALE, Money } from '@restaurantpro/ui';
import type { Partition } from '../settlement';

const quantity = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 3 });

/** Stitch's "Subcontas": the bill's live parts, each with what it still owes. */
export function PartitionList({ partitions }: { partitions: Partition[] }) {
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
            <li key={partition.id} className="flex flex-col gap-2 rounded-xl border bg-card p-4">
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
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
