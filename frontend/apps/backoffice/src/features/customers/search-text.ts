const SEPARATORS = /[\s+\-().]/g;
const MIN_DIGITS = 4;
const MIN_NAME_LENGTH = 3;

/**
 * The text to search with, or undefined when the server would refuse it (backend `CustomerQuery`, D72): written only
 * with digits and phone separators it is a phone suffix of at least 4 digits, anything else a name of at least 3
 * characters. Checked here only to spare the user a 400; the server decides.
 */
export function searchText(raw: string): string | undefined {
  const text = raw.trim();
  const digits = text.replace(SEPARATORS, '');
  if (/^\d+$/.test(digits)) return digits.length >= MIN_DIGITS ? text : undefined;
  return [...text].length >= MIN_NAME_LENGTH ? text : undefined;
}
