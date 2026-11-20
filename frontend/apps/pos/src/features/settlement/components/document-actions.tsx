import { Button } from '@restaurantpro/ui';
import { Printer } from 'lucide-react';
import { type FiscalDocument, type Reprint, useReprint } from '../hooks/use-settlement-commands';
import { CommandStatus } from './command-status';

/** "Documento A/42", its series and number as the server issued it. */
export function documentName(document: FiscalDocument): string {
  return document.seriesCode && document.number !== undefined ? `Documento ${document.seriesCode}/${document.number}` : 'Documento';
}

/** What the last document command of one target did, in one place named `label`. */
export function DocumentNotice({ label, issued, reprint }: { label: string; issued?: FiscalDocument; reprint?: Reprint }) {
  if (!issued && !reprint) return null;
  return (
    <p role="status" aria-label={label} className="font-medium">
      {reprint ? `2ª via${reprint.copyNo ? ` n.º ${reprint.copyNo}` : ''} enviada para impressão.` : `${documentName(issued!)} emitido.`}
    </p>
  );
}

/** "2ª via" of an issued document (`name` tells which one), with its status below. */
export function ReprintButton({ documentId, name, disabled, onPrinted }: {
  documentId: string;
  /** "de Parte 1 de 3"; none for the order's own. */
  name?: string;
  disabled: boolean;
  onPrinted: (reprint: Reprint | undefined) => void;
}) {
  const reprint = useReprint(onPrinted);
  return (
    <>
      <Button
        variant="outline"
        size="touch"
        disabled={disabled || reprint.intent !== undefined}
        onClick={() => reprint.start(documentId, undefined)}
      >
        <Printer aria-hidden="true" />
        {name ? `2ª via ${name}` : '2ª via'}
      </Button>
      <CommandStatus
        command={reprint}
        pendingText="A enviar a 2ª via…"
        unconfirmedText="Sem ligação — a 2ª via não foi confirmada."
        refusedText="A 2ª via não foi impressa."
        blocked={disabled}
      />
    </>
  );
}
