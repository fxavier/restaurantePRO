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
