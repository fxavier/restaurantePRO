/** The movements an operator records by hand (`ManualMovementRequest.type`): what the button and the outcome say. */
export const MOVEMENTS = {
  CASH_IN: { action: 'Entrada de numerário', done: 'Entrada de numerário registada' },
  CASH_OUT: { action: 'Saída de numerário', done: 'Saída de numerário registada' },
  EXPENSE: { action: 'Despesa autorizada', done: 'Despesa registada' },
} as const;

export type MovementType = keyof typeof MOVEMENTS;

/** The cash method's code (`CashMovement.CASH`): the drawer's own money, always counted. */
export const CASH = 'CASH';

/**
 * The methods the operator counts at the close: cash first, then each other method the session saw, from the
 * session's `expected` codes only. Their amounts are never read: the count is blind (P23).
 */
export function methodsToCount(expected: readonly { methodCode?: string }[] | undefined): string[] {
  const others = (expected ?? []).map((line) => line.methodCode).filter((code): code is string => !!code && code !== CASH);
  return [CASH, ...new Set(others)];
}

/** A method's name on screen: cash in Portuguese, any other by the code the store configured. */
export function methodLabel(code: string): string {
  return code === CASH ? 'Numerário' : code;
}

/** Whether a decimal string is zero ("0.00", "-0"): a test of its digits, not arithmetic. */
export function isZero(amount: string): boolean {
  return !/[1-9]/.test(amount);
}

type Amount = { amount: string; currency: string };

/** A closing's line per method (`ClosingLineView`), as the screen uses it: its method always named. */
export type ClosingLine = {
  methodCode: string;
  expected?: Amount;
  counted?: Amount;
  difference?: Amount;
  justification?: string;
};

/** The closing's lines as received, parsed once: a line that names no method cannot be shown or justified. */
export function closingLines(lines: readonly (Omit<ClosingLine, 'methodCode'> & { methodCode?: string })[] | undefined) {
  return (lines ?? []).flatMap(({ methodCode, ...line }): ClosingLine[] => (methodCode ? [{ methodCode, ...line }] : []));
}

/** The closing's lines with a difference: each needs a justification before the closing is submitted. */
export function linesWithDifference(lines: readonly ClosingLine[]): ClosingLine[] {
  return lines.filter((line) => line.difference !== undefined && !isZero(line.difference.amount));
}

/** Whether every difference has a justification on the server: only then may the closing be submitted. */
export function fullyJustified(lines: readonly ClosingLine[]): boolean {
  return linesWithDifference(lines).every((line) => !!line.justification?.trim());
}

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

/** What the operator typed in the movement dialog. */
export type TypedMovement = {
  type: MovementType;
  amount: string;
  reason: string;
  approverCode: string;
  approverPin: string;
};

/**
 * The movement's request body (`ManualMovementRequest`), or `undefined` while it cannot be sent: an amount, a reason
 * and, for an expense, the approver's code and PIN (P24). The approver goes in headers, never in the body.
 */
export function movementRequest(sessionId: string, typed: TypedMovement) {
  const amount = parseAmount(typed.amount);
  const reason = typed.reason.trim();
  const approved = typed.type !== 'EXPENSE' || (typed.approverCode.trim() !== '' && typed.approverPin !== '');
  if (amount === undefined || reason === '' || !approved) return undefined;
  return { sessionId, type: typed.type, amount, reason };
}
