import { Label } from '@restaurantpro/ui';
import { useId } from 'react';

/** AddLineRequest and UpdateLineRequest: notes of at most 200 characters. */
export const NOTE_MAX = 200;

/** A line's preparation note for the kitchen (§4, PRD 41), with the characters left. */
export function NoteField({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const id = useId();
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id} className="text-base font-semibold">
        Nota para a cozinha (opcional)
      </Label>
      <textarea
        id={id}
        value={value}
        maxLength={NOTE_MAX}
        rows={2}
        onChange={(event) => onChange(event.target.value)}
        aria-describedby={`${id}-count`}
        className="min-h-14 rounded-md border border-input bg-background px-3 py-2 text-base focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
      />
      <p id={`${id}-count`} className="text-right text-sm text-muted-foreground tabular-nums">
        {value.length}/{NOTE_MAX}
      </p>
    </div>
  );
}
