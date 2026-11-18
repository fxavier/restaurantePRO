import { Button, Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, LOCALE } from '@restaurantpro/ui';
import { Copy } from 'lucide-react';
import { useState } from 'react';
import type { EnrollmentCodeResponse } from '../types';

type EnrolmentCodeDialogProps = {
  /** The answer that holds the code; the dialog is open while it is set. */
  issued: EnrollmentCodeResponse | undefined;
  terminalName: string;
  /** The store's timezone, for the expiry (FC §12). */
  timeZone: string | undefined;
  /** Must forget the code: the caller resets the mutation that holds it. */
  onClose: () => void;
};

/**
 * The one-time enrolment code (stories 14, 15; D182): shown once, large, in groups of four (the POS accepts spaces),
 * with a copy button and its 24 h single-use note. Closing forgets it; it is never shown again.
 */
export function EnrolmentCodeDialog({ issued, terminalName, timeZone, onClose }: EnrolmentCodeDialogProps) {
  const [copy, setCopy] = useState<'idle' | 'copied' | 'failed'>('idle');
  const code = issued?.code ?? '';

  const close = () => {
    setCopy('idle');
    onClose();
  };

  const copyCode = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopy('copied');
    } catch {
      setCopy('failed');
    }
  };

  return (
    <Dialog open={issued !== undefined} onOpenChange={(open) => !open && close()}>
      <DialogContent showCloseButton={false} onInteractOutside={(event) => event.preventDefault()}>
        <DialogHeader>
          <DialogTitle>Código de registo de {terminalName}</DialogTitle>
          <DialogDescription>
            Introduza este código no terminal. Não volta a ser mostrado: se o perder, emita um novo.
          </DialogDescription>
        </DialogHeader>
        <p className="rounded-md border bg-muted px-4 py-5 text-center font-mono text-3xl font-semibold tracking-widest break-words select-all">
          {code.match(/.{1,4}/g)?.join(' ')}
        </p>
        <p className="text-sm">
          Válido durante 24 h e só pode ser usado uma vez
          {issued?.expiresAt && <> (expira a {formatInstant(issued.expiresAt, timeZone)})</>}.
        </p>
        <p role="status" className="min-h-5 text-sm text-muted-foreground">
          {copy === 'copied' && 'Código copiado.'}
          {copy === 'failed' && 'Não foi possível copiar. Selecione o código e copie-o.'}
        </p>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => void copyCode()}>
            <Copy aria-hidden="true" />
            Copiar código
          </Button>
          <Button type="button" onClick={close}>
            Fechar
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function formatInstant(iso: string, timeZone: string | undefined): string {
  return new Intl.DateTimeFormat(LOCALE, { dateStyle: 'short', timeStyle: 'short', timeZone }).format(new Date(iso));
}
