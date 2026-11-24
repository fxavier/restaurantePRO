import {
  Button,
  ConfirmButton,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Label,
} from '@restaurantpro/ui';
import { useId, useState } from 'react';
import { useChangeMode } from '../hooks/use-printer-commands';
import { printerModes } from '../labels';
import { PRINTER_MODES, type Printer } from '../parse';
import type { PrinterMode } from '../types';
import { NativeSelect } from './native-select';
import { ProblemAlert } from './problem-alert';

const hints: Record<PrinterMode, string> = {
  NORMAL: 'Imprime tudo o que lhe é encaminhado.',
  HOLD: 'Guarda os trabalhos na fila até voltar ao modo Normal.',
  IGNORE: 'Não imprime: os talões ficam registados como suprimidos.',
  REDIRECT: 'Envia o trabalho para outra impressora da loja, incluindo o que está por entregar.',
};

type ModeDialogProps = {
  printer: Printer | undefined;
  /** The store's printers: a redirect goes to another one that is not redirecting itself (no chains). */
  printers: Printer[];
  /** PRODUCTION_SUPPRESS in this store: only then is Ignorar offered. */
  maySuppress: boolean;
  onClose: () => void;
};

/**
 * Spec §8.6: Normal, Espera, Ignorar, Redirecionar. The form is mounted per printer version, so it opens on the
 * printer's mode and, after a conflict refetches the printer, shows the mode it has now. The command lives here so
 * its refusal stays on screen across that remount.
 */
export function ModeDialog({ printer, onClose, ...rest }: ModeDialogProps) {
  const change = useChangeMode();
  const close = () => {
    change.reset();
    onClose();
  };
  return (
    <Dialog open={printer !== undefined} onOpenChange={(open) => !open && close()}>
      <DialogContent showCloseButton={false}>
        <DialogHeader>
          <DialogTitle>Modo de {printer?.name}</DialogTitle>
          <DialogDescription>O que a impressora faz com os talões que lhe chegam.</DialogDescription>
        </DialogHeader>
        {printer && (
          <ModeForm key={`${printer.id}:${printer.version}`} printer={printer} change={change} onClose={close} {...rest} />
        )}
      </DialogContent>
    </Dialog>
  );
}

type ModeFormProps = ModeDialogProps & { printer: Printer; change: ReturnType<typeof useChangeMode> };

function ModeForm({ printer, printers, maySuppress, change, onClose }: ModeFormProps) {
  const [mode, setMode] = useState<PrinterMode>(printer.mode);
  const [target, setTarget] = useState(printer.redirectPrinterId ?? '');
  const baseId = useId();
  const targetId = `${baseId}-target`;
  const modes = PRINTER_MODES.filter((one) => one !== 'IGNORE' || maySuppress || printer.mode === 'IGNORE');
  const targets = printers.filter((other) => other.id !== printer.id && other.mode !== 'REDIRECT');
  const ready = mode !== 'REDIRECT' || target !== '';

  const submit = () =>
    change.mutate(
      {
        id: printer.id,
        body: { mode, version: printer.version, ...(mode === 'REDIRECT' ? { redirectPrinterId: target } : {}) },
      },
      { onSuccess: onClose },
    );

  return (
    <form
      className="flex flex-col gap-4"
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
    >
      <fieldset className="flex flex-col gap-2">
        <legend className="sr-only">Modo</legend>
        {modes.map((one) => {
          const { label, icon: Icon } = printerModes[one];
          const id = `${baseId}-${one}`;
          return (
            <div
              key={one}
              className="flex items-start gap-3 rounded-xl border p-3 has-checked:border-success-border has-checked:bg-success-tint"
            >
              <input
                id={id}
                type="radio"
                name="mode"
                value={one}
                checked={mode === one}
                onChange={() => setMode(one)}
                aria-describedby={`${id}-hint`}
                className="mt-1 size-4 accent-primary"
              />
              <span className="flex flex-col">
                <label htmlFor={id} className="flex cursor-pointer items-center gap-1.5 font-medium">
                  <Icon aria-hidden="true" className="size-4" />
                  {label}
                </label>
                <span id={`${id}-hint`} className="text-sm text-muted-foreground">
                  {hints[one]}
                </span>
              </span>
            </div>
          );
        })}
      </fieldset>
      {mode === 'REDIRECT' && (
        <div className="flex flex-col gap-2">
          <Label htmlFor={targetId}>Enviar para</Label>
          <NativeSelect id={targetId} value={target} onChange={(event) => setTarget(event.target.value)}>
            <option value="">Escolher impressora</option>
            {targets.map((other) => (
              <option key={other.id} value={other.id}>
                {other.name}
              </option>
            ))}
          </NativeSelect>
        </div>
      )}
      {change.error ? <ProblemAlert error={change.error} pending={change.isPending} onRetry={submit} /> : null}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose}>
          Cancelar
        </Button>
        <ConfirmButton type="submit" pending={change.isPending} disabled={!ready}>
          Mudar modo
        </ConfirmButton>
      </DialogFooter>
    </form>
  );
}
