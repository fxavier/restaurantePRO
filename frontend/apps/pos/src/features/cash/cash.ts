/**
 * An amount as typed ("1500", "1500,50", "1500.50") as the decimal string the contract takes ("1500.50"), or
 * `undefined` when it is not one. Text in, text out: the client does no money arithmetic (FC §12); the server checks
 * the currency's decimals.
 */
export function parseAmount(typed: string): string | undefined {
  const text = typed.trim().replace(',', '.');
  return /^\d+(\.\d+)?$/.test(text) ? text : undefined;
}
