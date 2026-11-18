import { ConfirmButton, Input, Label } from '@restaurantpro/ui';
import { type FormEvent, useEffect, useRef, useState } from 'react';
import { CODE_LENGTH, formatCode, normaliseCode } from '../enrolment-code';
import { CodeKeypad } from './code-keypad';

type Props = {
  onSubmit: (code: string) => void;
  /** The enrolment is in flight: nothing can change until the server answers. */
  pending: boolean;
  /** The last refusal, or why the terminal is back here; shown where the action is. */
  message?: string;
};

/**
 * The code, typed on the keypad or on a keyboard or scanner (§4), and one primary action. The message slot keeps its
 * height, so an error does not move the button.
 */
export function EnrolmentForm({ onSubmit, pending, message }: Props) {
  const [code, setCode] = useState('');
  const input = useRef<HTMLInputElement>(null);
  const complete = code.length === CODE_LENGTH;

  useEffect(() => input.current?.focus(), []);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (complete && !pending) onSubmit(code);
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <div className="flex flex-col gap-2">
        <Label htmlFor="enrolment-code" className="text-base">
          Código de registo
        </Label>
        <Input
          id="enrolment-code"
          ref={input}
          value={formatCode(code)}
          onChange={(event) => setCode(normaliseCode(event.target.value))}
          readOnly={pending}
          inputMode="none"
          autoComplete="off"
          autoCapitalize="characters"
          spellCheck={false}
          aria-describedby="enrolment-code-count"
          className="h-14 px-4 font-mono text-2xl tracking-wider md:text-2xl"
        />
        <p id="enrolment-code-count" className="text-sm text-muted-foreground tabular-nums">
          {code.length} de {CODE_LENGTH} caracteres
        </p>
      </div>
      <CodeKeypad
        disabled={pending}
        onCharacter={(character) => setCode(normaliseCode(code + character))}
        onDelete={() => setCode(code.slice(0, -1))}
      />
      <div className="min-h-12">
        {message && (
          <p role="alert" className="font-medium text-destructive">
            {message}
          </p>
        )}
      </div>
      <ConfirmButton type="submit" size="touch-primary" className="w-full" disabled={!complete} pending={pending}>
        Registar terminal
      </ConfirmButton>
    </form>
  );
}
