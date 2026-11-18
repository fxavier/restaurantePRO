/**
 * The one-time enrolment code (D182, backend `EnrolmentCode`): 8 characters of selector and 16 of secret, in Crockford
 * base32. The backend ignores case, spaces and dashes; the client also reads O, I and L as Crockford decodes them.
 * The code is a credential: never logged, never put in a URL or a query key.
 */
export const CODE_LENGTH = 24;
export const CODE_CHARACTERS = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

const LOOKALIKES: Record<string, string> = { O: '0', I: '1', L: '1' };

export function normaliseCode(typed: string): string {
  let code = '';
  for (const character of typed.toUpperCase()) {
    const read = LOOKALIKES[character] ?? character;
    if (CODE_CHARACTERS.includes(read)) code += read;
    if (code.length === CODE_LENGTH) break;
  }
  return code;
}

export function formatCode(code: string): string {
  return code.replace(/(.{4})(?=.)/g, '$1 ');
}
