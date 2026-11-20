import { cn } from '@restaurantpro/ui';

/** Stitch's label-over-value figures of a card (label-sm uppercase, then the value). */
export function Facts({ facts, className }: { facts: [label: string, value: string | number | undefined][]; className?: string }) {
  return (
    <dl className={cn('grid grid-cols-2 gap-4', className)}>
      {facts.map(([label, value]) => (
        <div key={label} className="flex flex-col gap-1">
          <dt className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">{label}</dt>
          <dd className="text-lg font-semibold break-words">{value ?? '—'}</dd>
        </div>
      ))}
    </dl>
  );
}
