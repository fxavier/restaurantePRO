import { Button } from '@restaurantpro/ui';
import { Delete } from 'lucide-react';
import type { PointerEvent } from 'react';

type Props = {
  onDigit: (digit: string) => void;
  onDelete: () => void;
  onClear: () => void;
  disabled?: boolean;
};

/** Keeps the focus in the field, so a keyboard or scanner still types there after a tap. */
const keepFocus = (event: PointerEvent) => event.preventDefault();

/** DS: PIN pad keys are 72 × 72 px, 12 px apart (white and rounded: the light reference's outline button). */
const SHAPE = 'size-18 px-0';
const KEY = `${SHAPE} text-3xl font-semibold tabular-nums`;

/** The numeric pad of the PIN screens: 1–9, then "Limpar", 0 and "Apagar", as on a phone. */
export function PinPad({ onDigit, onDelete, onClear, disabled = false }: Props) {
  const key = (label: string, onClick: () => void, className = KEY) => (
    <Button
      key={label}
      type="button"
      variant="outline"
      size="touch"
      className={className}
      onPointerDown={keepFocus}
      onClick={onClick}
      disabled={disabled}
    >
      {label === 'Apagar' && <Delete aria-hidden="true" />}
      {label}
    </Button>
  );
  return (
    <div role="group" aria-label="Teclado numérico" className="grid grid-cols-3 gap-3">
      {['1', '2', '3', '4', '5', '6', '7', '8', '9'].map((digit) => key(digit, () => onDigit(digit)))}
      {key('Limpar', onClear, `${SHAPE} text-base`)}
      {key('0', () => onDigit('0'))}
      {key('Apagar', onDelete, `${SHAPE} flex-col gap-0 text-sm`)}
    </div>
  );
}
