/** The movements an operator records by hand (`ManualMovementRequest.type`): what the button and the outcome say. */
export const MOVEMENTS = {
  CASH_IN: { action: 'Entrada de numerário', done: 'Entrada de numerário registada' },
  CASH_OUT: { action: 'Saída de numerário', done: 'Saída de numerário registada' },
  EXPENSE: { action: 'Despesa autorizada', done: 'Despesa registada' },
} as const;

export type MovementType = keyof typeof MOVEMENTS;

/** Whether the employee holds `PERM_<name>` in their store: for showing controls only, the backend enforces. */
export function holds(employee: { permissions: string[]; storeId: string } | undefined, name: string): boolean {
  return employee?.permissions.includes(`PERM_${name}@${employee.storeId}`) ?? false;
}

/**
 * An amount as typed ("1500", "1500,50", "1500.50") as the decimal string the contract takes ("1500.50"), or
 * `undefined` when it is not one. Text in, text out: the client does no money arithmetic (FC §12); the server checks
 * the currency's decimals.
 */
export function parseAmount(typed: string): string | undefined {
  const text = typed.trim().replace(',', '.');
  return /^\d+(\.\d+)?$/.test(text) ? text : undefined;
}
