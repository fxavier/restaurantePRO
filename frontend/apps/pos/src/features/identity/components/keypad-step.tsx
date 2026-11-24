import { ConfirmButton, Input, Label } from '@restaurantpro/ui';
import { type FormEvent, type ReactNode, useEffect, useRef } from 'react';
import { PinPad } from './pin-pad';

type Props = {
  /** The field's id and label. */
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  /** Applied to everything typed or tapped. */
  normalise: (typed: string) => string;
  /** A PIN: the digits are hidden. */
  secret?: boolean;
  canSubmit: boolean;
  /** The request is in flight: nothing changes until the server answers. */
  pending: boolean;
  submitLabel: string;
  onSubmit: () => void;
  /** The last refusal, shown where the action is. */
  error?: string;
  /** Why the screen is here (the previous session ended), when there is no error. */
  notice?: string;
  /** Above the field: the heading and what the step is about. */
  children: ReactNode;
};

/**
 * One field of a PIN screen, typed on the pad or on a keyboard or scanner (§4, Enter submits), with one primary
 * action. The field opens no on-screen keyboard (the pad is there), and the message slot keeps its height, so an
 * error moves nothing.
 */
export function KeypadStep({
  id,
  label,
  value,
  onChange,
  normalise,
  secret = false,
  canSubmit,
  pending,
  submitLabel,
  onSubmit,
  error,
  notice,
  children,
}: Props) {
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => input.current?.focus(), []);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (canSubmit && !pending) onSubmit();
  }

  return (
    // Light reference: a white rounded card centred on the grey page; the field and its action left, the pad right.
    <form onSubmit={submit} className="flex w-full max-w-3xl flex-col gap-8 rounded-2xl border bg-card p-6 sm:flex-row sm:p-8">
      <div className="flex min-w-0 flex-1 flex-col gap-4">
        {children}
        <div className="flex flex-col gap-2">
          <Label htmlFor={id} className="text-base">
            {label}
          </Label>
          <Input
            id={id}
            ref={input}
            type={secret ? 'password' : 'text'}
            value={value}
            onChange={(event) => onChange(normalise(event.target.value))}
            readOnly={pending}
            inputMode="none"
            autoComplete="off"
            autoCapitalize="characters"
            spellCheck={false}
            className="h-14 px-4 font-mono text-2xl tracking-widest md:text-2xl"
          />
        </div>
        <div className="min-h-18">
          {error ? (
            <p role="alert" className="font-medium text-error">
              {error}
            </p>
          ) : (
            notice && (
              <p role="status" className="font-medium text-muted-foreground">
                {notice}
              </p>
            )
          )}
        </div>
        <ConfirmButton type="submit" size="touch-primary" className="mt-auto w-full" disabled={!canSubmit} pending={pending}>
          {submitLabel}
        </ConfirmButton>
      </div>
      <PinPad
        disabled={pending}
        onDigit={(digit) => onChange(normalise(value + digit))}
        onDelete={() => onChange(value.slice(0, -1))}
        onClear={() => onChange('')}
      />
    </form>
  );
}
