const DECIMAL = /^([+-]?)(\d+)(?:[.,](\d+))?$/;

/**
 * A quantity or a cost as typed in pt (`0,5`, `-2`, `12.75`) into the decimal string the contract carries (FC §12):
 * the text is rewritten, never parsed into a float. Undefined when it is not a plain decimal number. Thousands
 * separators are refused rather than guessed.
 */
export function toDecimal(input: string): string | undefined {
  const match = DECIMAL.exec(input.trim());
  if (!match) return undefined;
  const [, sign, whole, fraction] = match;
  return `${sign === '-' ? '-' : ''}${whole}${fraction === undefined ? '' : `.${fraction}`}`;
}

/** Whether a decimal string (as `toDecimal` writes it) is above zero, by its digits alone. */
export function isPositive(decimal: string): boolean {
  return !decimal.startsWith('-') && /[1-9]/.test(decimal);
}

/**
 * The order of two decimal strings of zero or more (as `toDecimal` or the contract writes them: `4.5`, `5.000000`),
 * by their digits alone, never through a float: -1, 0 or 1.
 */
export function compareDecimals(a: string, b: string): -1 | 0 | 1 {
  const [aWhole = '', aFraction = ''] = a.split('.');
  const [bWhole = '', bFraction = ''] = b.split('.');
  const width = Math.max(aFraction.length, bFraction.length);
  const digits = (whole: string, fraction: string) => `${whole.replace(/^0+/, '')}.${fraction.padEnd(width, '0')}`;
  const [x, y] = [digits(aWhole, aFraction), digits(bWhole, bFraction)];
  if (x.length !== y.length) return x.length < y.length ? -1 : 1;
  return x === y ? 0 : x < y ? -1 : 1;
}
