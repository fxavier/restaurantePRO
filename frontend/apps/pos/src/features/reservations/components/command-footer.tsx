import { Button, ConfirmButton, DialogFooter } from '@restaurantpro/ui';
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
