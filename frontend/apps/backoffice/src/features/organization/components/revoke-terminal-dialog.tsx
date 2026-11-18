import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@restaurantpro/ui';
import type { TerminalSummary } from '../types';

type RevokeTerminalDialogProps = {
  terminal: TerminalSummary | null;
  onConfirm: (terminal: TerminalSummary) => void;
  onCancel: () => void;
};

/** Story 16: revoking stops the device at once, so it is confirmed first. */
export function RevokeTerminalDialog({ terminal, onConfirm, onCancel }: RevokeTerminalDialogProps) {
  return (
    <AlertDialog open={terminal !== null} onOpenChange={(open) => !open && onCancel()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Revogar o terminal {terminal?.name}?</AlertDialogTitle>
          <AlertDialogDescription>
            O terminal deixa de funcionar de imediato e não pode voltar a ser ativado. Para o usar de novo, registe-o
            outra vez.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancelar</AlertDialogCancel>
          <AlertDialogAction variant="destructive" onClick={() => terminal && onConfirm(terminal)}>
            Revogar
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
