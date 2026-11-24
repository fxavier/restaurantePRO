import { Card, cn, TONE_TINT, type StatusTone } from '@restaurantpro/ui';
import { CircleHelp, CircleX, Printer, Unplug, type LucideIcon } from 'lucide-react';

export type SummaryCounts = {
  /** Counts from the server's answers; undefined while unknown, shown as a dash. */
  activePrinters: number | undefined;
  printers: number | undefined;
  failed: number | undefined;
  unknown: number | undefined;
  unrouted: number | undefined;
};

/**
 * Stitch's stat cards: what is running and what needs someone, each with its icon and label (never colour alone). The
 * icon sits on its state's tint (the shared TONE_TINT, AA text on the tint).
 */
export function ProductionSummary({ activePrinters, printers, failed, unknown, unrouted }: SummaryCounts) {
  const stats: { label: string; value: string; icon: LucideIcon; tone: StatusTone }[] = [
    {
      label: 'Impressoras ativas',
      value: printers === undefined ? '—' : `${activePrinters} / ${printers}`,
      icon: Printer,
      tone: 'success',
    },
    // The server keeps a resolved job's status and the list has no "unresolved" filter: these count every one.
    { label: 'Falhados (total)', value: count(failed), icon: CircleX, tone: 'destructive' },
    { label: 'Desfecho desconhecido (total)', value: count(unknown), icon: CircleHelp, tone: 'info' },
    { label: 'Talões por encaminhar', value: count(unrouted), icon: Unplug, tone: 'warning' },
  ];
  return (
    <section aria-label="Resumo" className="mt-6">
      <dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {stats.map(({ label, value, icon: Icon, tone }) => (
          <Card key={label} className="gap-2 p-4">
            <dt className="flex items-start justify-between gap-3 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
              {label}
              <span className={cn('flex size-10 shrink-0 items-center justify-center rounded-xl', TONE_TINT[tone])}>
                <Icon aria-hidden="true" className="size-5" />
              </span>
            </dt>
            <dd className="text-3xl font-semibold tabular-nums">{value}</dd>
          </Card>
        ))}
      </dl>
      <p className="mt-2 text-xs text-muted-foreground">Os totais incluem os trabalhos já reenviados ou confirmados.</p>
    </section>
  );
}

function count(value: number | undefined): string {
  return value === undefined ? '—' : String(value);
}
