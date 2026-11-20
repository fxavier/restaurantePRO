import { Badge, StatusBadge } from '@restaurantpro/ui';
import { Printer } from 'lucide-react';
import { useId, type ReactNode } from 'react';
import { printerModes } from '../labels';
import type { Printer as PrinterData } from '../parse';

type PrinterCardProps = {
  printer: PrinterData;
  /** The printer a REDIRECT sends to, by name. */
  redirectName: string | undefined;
  /** The destination codes routed here and the terminals printing here, by name. */
  destinations: string[];
  terminals: string[];
  /** The printer's actions. */
  children?: ReactNode;
};

/** Stitch's device card: who it is, where it is, its mode, and what is routed to it. */
export function PrinterCard({ printer, redirectName, destinations, terminals, children }: PrinterCardProps) {
  const titleId = useId();
  return (
    <li aria-labelledby={titleId} className="flex flex-col gap-4 rounded-xl border bg-card p-4">
      <div className="flex flex-wrap items-start gap-3">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-md bg-primary/15 text-primary">
          <Printer aria-hidden="true" className="size-5" />
        </span>
        <div className="min-w-0 flex-1">
          <h3 id={titleId} className="font-semibold">
            {printer.name}
          </h3>
          <p className="flex flex-wrap gap-x-2 font-mono text-sm text-muted-foreground">
            <span>
              <span className="sr-only">Código </span>
              {printer.code}
            </span>
            <span>
              {printer.host}:{printer.port}
            </span>
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <StatusBadge status={printer.mode} statuses={printerModes} />
          {!printer.active && <Badge variant="secondary">Inativa</Badge>}
        </div>
      </div>
      {printer.mode === 'REDIRECT' && redirectName && <p className="text-sm">Envia para {redirectName}</p>}
      <Chips title="Destinos encaminhados" items={destinations} empty="Sem destinos encaminhados." mono />
      {terminals.length > 0 && <Chips title="Terminais" items={terminals} />}
      {children && <div className="flex flex-wrap justify-end gap-2 border-t pt-3">{children}</div>}
    </li>
  );
}

function Chips({ title, items, empty, mono = false }: { title: string; items: string[]; empty?: string; mono?: boolean }) {
  return (
    <div className="flex flex-col gap-1.5">
      <p className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">{title}</p>
      {items.length ? (
        <ul aria-label={title} className="flex flex-wrap gap-1.5">
          {items.map((item) => (
            <li key={item}>
              <Badge variant="outline" className={mono ? 'font-mono' : undefined}>
                {item}
              </Badge>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-muted-foreground">{empty}</p>
      )}
    </div>
  );
}
