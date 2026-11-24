import type { FamilySummary } from '../types';

type FamilyPillsProps = {
  families: readonly FamilySummary[];
  counts: ReadonlyMap<string, number>;
  total: number;
  selected: string | undefined;
  onSelect: (familyId: string | undefined) => void;
};

/**
 * The family pills with their counts, as the POS's area tabs: white bordered pills, the pressed one solid green; one
 * pressed at a time, "Todas as famílias" first.
 */
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
      className="group inline-flex h-10 items-center gap-2 rounded-xl border bg-card px-3 text-sm font-medium hover:bg-muted focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring aria-pressed:border-primary aria-pressed:bg-primary aria-pressed:font-semibold aria-pressed:text-primary-foreground aria-pressed:hover:bg-primary-hover"
    >
      {label}
      {/* The count on the muted surface, white on the pressed (green) pill: foreground text on both (AA). */}
      <span className="rounded-md bg-muted px-1.5 text-xs text-foreground tabular-nums group-aria-pressed:bg-card">{count}</span>
    </button>
  );
}
