import { Button, Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@restaurantpro/ui';
import { type FormEvent, useState } from 'react';
import { NoteField } from './note-field';

type Props = { name: string; notes?: string; onSave: (notes: string | undefined) => void; onCancel: () => void };

/** A draft line's note, changed after it was added (PRD 41); an empty note removes it. */
export function NoteDialog({ name, notes: current, onSave, onCancel }: Props) {
  const [notes, setNotes] = useState(current ?? '');

  function submit(event: FormEvent) {
    event.preventDefault();
    onSave(notes.trim() || undefined);
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onCancel()}>
      <DialogContent showCloseButton={false} className="sm:max-w-lg">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>Nota de {name}</DialogTitle>
            <DialogDescription>A nota segue com a linha para a produção.</DialogDescription>
          </DialogHeader>
          <NoteField value={notes} onChange={setNotes} />
          <DialogFooter>
            <Button type="button" variant="outline" size="touch" onClick={onCancel}>
              Cancelar
            </Button>
            <Button type="submit" size="touch-primary">
              Guardar
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
