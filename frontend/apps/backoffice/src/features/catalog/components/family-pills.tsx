import type { FamilySummary } from '../types';

type FamilyPillsProps = {
  families: readonly FamilySummary[];
  counts: ReadonlyMap<string, number>;
  total: number;
  selected: string | undefined;
  onSelect: (familyId: string | undefined) => void;
};

/** Stitch's family pills with their counts: one pressed at a time, "Todas as famílias" first. */
export function FamilyPills({ families, counts, total, selected, onSelect }: FamilyPillsProps) {
  return (
    <div role="group" aria-label="Famílias" className="flex flex-wrap items-center gap-2">
      <Pill label="Todas as famílias" count={total} pressed={!selected} onClick={() => onSelect(undefined)} />
      {families.map((family) => (
        <Pill
          key={family.id}
          label={family.name ?? ''}
          count={counts.get(family.id ?? '') ?? 0}
          pressed={selected === family.id}
          onClick={() => onSelect(family.id)}
        />
      ))}
    </div>
  );
}

function Pill({ label, count, pressed, onClick }: { label: string; count: number; pressed: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      onClick={onClick}
      className="inline-flex h-9 items-center gap-2 rounded-md border bg-muted px-3 text-sm font-semibold focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring aria-pressed:border-primary aria-pressed:bg-primary aria-pressed:text-primary-foreground"
    >
      {label}
      <span className="rounded-sm bg-background/40 px-1.5 text-xs tabular-nums">{count}</span>
    </button>
  );
}
