import { Button } from '@restaurantpro/ui';
import { Delete } from 'lucide-react';
import type { PointerEvent } from 'react';
import { CODE_CHARACTERS } from '../enrolment-code';

type Props = {
  onCharacter: (character: string) => void;
  onDelete: () => void;
  disabled?: boolean;
};

/** Keeps the focus in the code field, so a keyboard or scanner still types there after a tap. */
const keepFocus = (event: PointerEvent) => event.preventDefault();

// ponytail: below `sm` five 48 px columns (the touch minimum) instead of eleven 64 px ones, so a handheld fits it.
// Light reference: white keys, rounded as its buttons.
const SIZE = 'size-16 rounded-xl bg-card max-sm:h-12 max-sm:w-full';
const KEY = `${SIZE} px-0 font-mono text-2xl font-semibold`;

/**
 * The code's 32 characters on 64 px keys (DS: ≥ 48 px, ≥ 8 px apart): digits and "Apagar" on the first row, the
 * letters in two rows below, in the order the code alphabet uses.
 */
export function CodeKeypad({ onCharacter, onDelete, disabled = false }: Props) {
  const digits = CODE_CHARACTERS.slice(0, 10);
  const letters = CODE_CHARACTERS.slice(10);
  return (
    <div role="group" aria-label="Teclado do código" className="grid grid-cols-5 gap-2 max-sm:w-full sm:grid-cols-11 sm:self-center">
      {digits.split('').map((character) => (
        <Key key={character} character={character} onCharacter={onCharacter} disabled={disabled} />
      ))}
      <Button
        type="button"
        variant="outline"
        size="touch"
        className={`${SIZE} flex-col gap-0 px-0 text-sm`}
        onPointerDown={keepFocus}
        onClick={onDelete}
        disabled={disabled}
      >
        <Delete aria-hidden="true" />
        Apagar
      </Button>
      {letters.split('').map((character) => (
        <Key key={character} character={character} onCharacter={onCharacter} disabled={disabled} />
      ))}
    </div>
  );
}

function Key({ character, onCharacter, disabled }: { character: string; onCharacter: Props['onCharacter']; disabled: boolean }) {
  return (
    <Button
      type="button"
      variant="outline"
      size="touch"
      className={KEY}
      onPointerDown={keepFocus}
      onClick={() => onCharacter(character)}
      disabled={disabled}
    >
      {character}
    </Button>
  );
}
