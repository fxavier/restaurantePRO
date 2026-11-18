/**
 * The employee code and the PIN (D26): a PIN is 4 to 8 digits; a code is up to 20 characters (backend
 * `PosLoginRequest`), digits on the pad, any character from a keyboard or a badge scanner. Both are credentials: never
 * logged, never put in a URL, a query key or storage.
 */
export const PIN_MIN = 4;
export const PIN_MAX = 8;
export const CODE_MAX = 20;

export function normalisePin(typed: string): string {
  return typed.replace(/\D/g, '').slice(0, PIN_MAX);
}

/** A scanner may add spaces or a line end; the backend compares the code as it is otherwise. */
export function normaliseCode(typed: string): string {
  return typed.replace(/\s/g, '').slice(0, CODE_MAX);
}

export const isPin = (pin: string) => pin.length >= PIN_MIN && pin.length <= PIN_MAX;
