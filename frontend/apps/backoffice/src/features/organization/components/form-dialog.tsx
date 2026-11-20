import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from '@restaurantpro/ui';
import { useState, type ReactNode } from 'react';

type FormDialogProps = {
  /** The button that opens it. */
  trigger: ReactNode;
  title: string;
  description: string;
  /** The form; `close` ends the dialog (after a save, or Cancelar). Mounted only while open: each opening is new. */
  children: (close: () => void) => ReactNode;
};

/** A configuration form in a dialog (Stitch's modal: card surface, xl radius). */
export function FormDialog({ trigger, title, description, children }: FormDialogProps) {
  const [open, setOpen] = useState(false);
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>{trigger}</DialogTrigger>
      <DialogContent showCloseButton={false} className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        {children(() => setOpen(false))}
      </DialogContent>
    </Dialog>
  );
}
