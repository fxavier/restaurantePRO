import { Button, ConfirmButton, Dialog, DialogContent, DialogFooter } from '@restaurantpro/ui';
import type { ReactNode } from 'react';
import { WifiOff } from 'lucide-react';
import { OFFLINE } from '../../../lib/error-messages';
import { useCommandsBlocked } from '../../../lib/live/use-connection';

type Props = {
  /** The command's own label ("Sentar", "Guardar reserva"). */
  label: string;
  /** The refusal to show, if the last attempt failed. */
  error: string | undefined;
  pending: boolean;
  /** An unknown outcome waits: the button resends the same intent. */
  awaitingRetry: boolean;
  /** The input is not complete yet. */
  disabled?: boolean;
  onClose: () => void;
  variant?: 'default' | 'destructive';
};

/**
 * The bottom of every reservation dialog: one line kept for the message (so the buttons never move), "Voltar", and
 * the one confirming button, disabled while in flight and while the terminal has no connection (FC §11).
 */
export function CommandFooter({ label, error, pending, awaitingRetry, disabled = false, onClose, variant }: Props) {
  const blocked = useCommandsBlocked();
  return (
    <>
      <div className="min-h-12">
        {blocked ? (
          <p className="flex items-center gap-2 font-medium text-error">
            <WifiOff aria-hidden="true" className="size-5 shrink-0" />
            {OFFLINE}
          </p>
        ) : error ? (
          <p role="alert" className="font-medium text-error">
            {error}
          </p>
        ) : null}
      </div>
      <DialogFooter>
        <Button type="button" variant="outline" size="touch" onClick={onClose} disabled={pending}>
          Voltar
        </Button>
        <ConfirmButton type="submit" size="touch-primary" variant={variant} pending={pending} disabled={blocked || disabled}>
          {awaitingRetry ? 'Tentar novamente' : label}
        </ConfirmButton>
      </DialogFooter>
    </>
  );
}

/**
 * Closing a command's dialog gives its intent up: one whose outcome is unknown may still have happened, so the intent
 * reads again what it changes (`onDiscard`), and the next open is a new intent.
 */
export function giveUp(intent: { discard: () => void }, onClose: () => void): () => void {
  return () => {
    intent.discard();
    onClose();
  };
}

type DialogProps = {
  pending: boolean;
  onClose: () => void;
  /** The reservation form's width, scrolling within the screen's height. */
  wide?: boolean;
  children: ReactNode;
};

/** A reservation command's dialog: it cannot be dismissed while its command is in flight. */
export function CommandDialog({ pending, onClose, wide = false, children }: DialogProps) {
  return (
    <Dialog open onOpenChange={(open) => !open && !pending && onClose()}>
      <DialogContent
        showCloseButton={false}
        className={wide ? 'max-h-screen overflow-y-auto rounded-2xl sm:max-w-3xl' : 'rounded-2xl sm:max-w-lg'}
      >
        {children}
      </DialogContent>
    </Dialog>
  );
}
