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

type ConfirmDialogProps<Target> = {
  /** What the command acts on; the dialog is open while there is one. */
  target: Target | null;
  title: (target: Target | null) => string;
  description: string;
  action: string;
  destructive?: boolean;
  onConfirm: (target: Target) => void;
  onCancel: () => void;
};

/** A command that cannot be undone from here is confirmed first, with one deliberate action. */
export function ConfirmDialog<Target>({ target, title, description, action, destructive, onConfirm, onCancel }: ConfirmDialogProps<Target>) {
  return (
    <AlertDialog open={target !== null} onOpenChange={(open) => !open && onCancel()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title(target)}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancelar</AlertDialogCancel>
          <AlertDialogAction variant={destructive ? 'destructive' : 'default'} onClick={() => target !== null && onConfirm(target)}>
            {action}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
