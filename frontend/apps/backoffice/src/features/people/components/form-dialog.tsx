import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@restaurantpro/ui';
import type { ReactNode } from 'react';

type FormDialogProps = {
  title: string;
  description: string;
  open: boolean;
  onClose: () => void;
  /** Mounted only while open, so each opening is a new intent with an empty form. */
  children: ReactNode;
};

/** A people command's form in a dialog. */
export function FormDialog({ title, description, open, onClose, children }: FormDialogProps) {
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent showCloseButton={false}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        {open && children}
      </DialogContent>
    </Dialog>
  );
}
