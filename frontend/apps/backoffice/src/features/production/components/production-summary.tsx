import { CircleHelp, CircleX, Printer, Unplug, type LucideIcon } from 'lucide-react';

export type SummaryCounts = {
  /** Counts from the server's answers; undefined while unknown, shown as a dash. */
  activePrinters: number | undefined;
  printers: number | undefined;
  failed: number | undefined;
  unknown: number | undefined;
  unrouted: number | undefined;
};

/** Stitch's stat cards: what is running and what needs someone, each with its icon and label (never colour alone). */
export function ProductionSummary({ activePrinters, printers, failed, unknown, unrouted }: SummaryCounts) {
  const stats: { label: string; value: string; icon: LucideIcon; tint: string }[] = [
    {
      label: 'Impressoras ativas',
      value: printers === undefined ? '—' : `${activePrinters} / ${printers}`,
      icon: Printer,
      tint: 'bg-primary/15 text-primary',
    },
    { label: 'Trabalhos falhados', value: count(failed), icon: CircleX, tint: 'bg-destructive/15 text-error' },
    { label: 'Por confirmar', value: count(unknown), icon: CircleHelp, tint: 'bg-info/15 text-foreground' },
    { label: 'Talões por encaminhar', value: count(unrouted), icon: Unplug, tint: 'bg-warning/15 text-warning' },
  ];
  return (
    <section aria-label="Resumo" className="mt-6">
      <dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {stats.map(({ label, value, icon: Icon, tint }) => (
          <div key={label} className="flex flex-col gap-2 rounded-xl border bg-card p-4">
            <dt className="flex items-start justify-between gap-3 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
              {label}
              <span className={`flex size-10 shrink-0 items-center justify-center rounded-md ${tint}`}>
                <Icon aria-hidden="true" className="size-5" />
              </span>
            </dt>
            <dd className="text-3xl font-semibold tabular-nums">{value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

function count(value: number | undefined): string {
  return value === undefined ? '—' : String(value);
}
