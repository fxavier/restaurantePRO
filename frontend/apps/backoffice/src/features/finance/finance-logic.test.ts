import { describe, expect, it } from 'vitest';
import type { PendingCommand } from '../../lib/step-up';
import { commandLabel, financePath, fromPendingCommand, holds, toPendingCommand, type FinanceCommand } from './finance-commands';
import { cashDays, closeKindLabel, closingStatuses, dayStatuses, nameById } from './finance-labels';
import { formatBusinessDate, formatInstant } from './format';
import { dates, instants, lastDays, offsetOn, storeToday } from './period';

const STORE = '11111111-0000-4000-8000-000000000001';
const OTHER = '22222222-0000-4000-8000-000000000002';
const ID = 'cccc0000-0000-4000-8000-000000000001';
const KEY = 'abcd0000-0000-4000-8000-000000000001';

const commands: FinanceCommand[] = [
  { type: 'approveClosing', closingId: ID, label: 'CX1', idempotencyKey: KEY },
  { type: 'rejectClosing', closingId: ID, label: 'CX1', reason: 'Recontar', idempotencyKey: KEY },
  { type: 'financialClose', dayId: ID, label: '19/11/2026', idempotencyKey: KEY },
  { type: 'createSeries', body: { storeId: STORE, code: 'FR', firstNumber: 5 } },
  { type: 'deactivateSeries', seriesId: ID, label: 'FR' },
  { type: 'createMethod', body: { code: 'MPESA', name: 'M-Pesa', kind: 'EXTERNAL_TERMINAL' } },
  { type: 'updateMethod', methodId: ID, body: { name: 'M-Pesa', active: false, version: 3 } },
];

const stored = (command: FinanceCommand, storeId = STORE): PendingCommand => ({
  ...toPendingCommand(command, storeId, 'tenant-a'),
  savedAt: 0,
});

describe('finance commands kept for a step-up', () => {
  it.each(commands)('reads back $type as it was kept, for the same store', (command) => {
    expect(fromPendingCommand(stored(command), STORE)).toEqual(command);
  });

  it('comes back to the tab of each command', () => {
    expect(stored(commands[0]!).returnTo).toBe(financePath(STORE, 'fechos'));
    expect(stored(commands[3]!).returnTo).toBe(`/finance?storeId=${STORE}&tab=fiscal`);
    expect(stored(commands[6]!).returnTo).toBe(`/finance?storeId=${STORE}&tab=meios`);
  });

  it('keeps the request without a key where the contract takes none', () => {
    expect(stored(commands[3]!)).toMatchObject({ method: 'POST', path: '/api/v1/backoffice/fiscal/series', idempotencyKey: null });
    expect(stored(commands[6]!)).toMatchObject({ method: 'PUT', path: `/api/v1/backoffice/payment-methods/${ID}` });
    expect(stored(commands[1]!)).toMatchObject({ body: { reason: 'Recontar' }, idempotencyKey: KEY, label: 'CX1' });
    expect(stored(commands[3]!).label).toBe('');
  });

  it("leaves another store's or another screen's command alone", () => {
    expect(fromPendingCommand(stored(commands[0]!, OTHER), STORE)).toBeUndefined();
    expect(fromPendingCommand({ ...stored(commands[0]!), path: `/api/v1/backoffice/terminals/${ID}/revoke` }, STORE)).toBeUndefined();
    expect(fromPendingCommand({ ...stored(commands[0]!), returnTo: financePath(STORE, 'fiscal') }, STORE)).toBeUndefined();
  });

  it('refuses a keyed command without its key, a wrong method or a malformed body', () => {
    for (const command of commands.slice(0, 3)) {
      expect(fromPendingCommand({ ...stored(command), idempotencyKey: null }, STORE)).toBeUndefined();
    }
    expect(fromPendingCommand({ ...stored(commands[0]!), method: 'PUT' }, STORE)).toBeUndefined();
    expect(fromPendingCommand({ ...stored(commands[1]!), body: { reason: '' } }, STORE)).toBeUndefined();
    expect(fromPendingCommand({ ...stored(commands[3]!), body: { code: 'FR' } }, STORE)).toBeUndefined();
    expect(fromPendingCommand({ ...stored(commands[5]!), body: { code: 'X', name: 'Y', kind: 'CHEQUE' } }, STORE)).toBeUndefined();
    expect(fromPendingCommand({ ...stored(commands[6]!), body: { name: 'Y', active: true } }, STORE)).toBeUndefined();
    expect(fromPendingCommand({ ...stored(commands[0]!), path: `/api/v1/backoffice/cash/closings/${ID}/approve/x` }, STORE)).toBeUndefined();
  });

  it('names each command for the resume card', () => {
    expect(commands.map(commandLabel)).toEqual([
      'Aprovar o fecho CX1',
      'Rejeitar o fecho CX1',
      'Fechar financeiramente o dia 19/11/2026',
      'Criar a série FR',
      'Desativar a série FR',
      'Criar o meio de pagamento M-Pesa',
      'Alterar o meio de pagamento M-Pesa',
    ]);
  });
});

describe('permissions', () => {
  it('holds a permission company-wide or in this store only', () => {
    expect(holds(['PERM_CASH_CLOSING_APPROVE'], 'CASH_CLOSING_APPROVE', STORE)).toBe(true);
    expect(holds([`PERM_CASH_CLOSING_APPROVE@${STORE}`], 'CASH_CLOSING_APPROVE', STORE)).toBe(true);
    expect(holds([`PERM_CASH_CLOSING_APPROVE@${OTHER}`], 'CASH_CLOSING_APPROVE', STORE)).toBe(false);
    expect(holds(['PERM_CASH_VIEW'], 'CASH_CLOSING_APPROVE', STORE)).toBe(false);
    expect(holds(undefined, 'CASH_VIEW', STORE)).toBe(false);
  });
});

describe('cash days', () => {
  it('lists each day once, newest first, with its state', () => {
    const line = (cashDayId: string, businessDate: string, dayStatus: string) => ({ cashDayId, businessDate, dayStatus });
    expect(
      cashDays([line('a', '2026-11-18', 'FINANCIALLY_CLOSED'), line('b', '2026-11-19', 'OPEN'), line('a', '2026-11-18', 'X'), { methodCode: 'CASH' }]),
    ).toEqual([
      { id: 'b', businessDate: '2026-11-19', status: 'OPEN' },
      { id: 'a', businessDate: '2026-11-18', status: 'FINANCIALLY_CLOSED' },
    ]);
    expect(cashDays([{ cashDayId: 'c' }])).toEqual([{ id: 'c', businessDate: '', status: '' }]);
  });

  it("names a register by the store's registers, or by its id until they are read", () => {
    const registers = [{ id: 'r1', name: 'Caixa principal' }, { id: 'r2', name: 'Bar' }];
    expect(nameById(registers, 'r2')).toBe('Bar');
    expect(nameById(registers, 'r3')).toBe('r3');
    expect(nameById(undefined, 'r1')).toBe('r1');
    expect(nameById(registers, undefined)).toBe('');
  });

  it('says how a session was closed, nothing while it is open, and an unknown kind as the server named it', () => {
    expect(closeKindLabel('FORCED')).toBe('Forçado pela gerência');
    expect(closeKindLabel(null)).toBe('—');
    expect(closeKindLabel('AUTO')).toBe('AUTO');
  });

  it('labels known states and shows an unknown one as the server named it', () => {
    expect(closingStatuses('SUBMITTED').SUBMITTED!.label).toBe('Submetido');
    expect(dayStatuses('OPEN').OPEN!.label).toBe('Aberto');
    expect(dayStatuses('LATER').LATER!).toMatchObject({ label: 'LATER', tone: 'neutral' });
    expect(closingStatuses(undefined)['']!.label).toBe('—');
  });
});

describe('period', () => {
  const now = new Date('2026-11-19T22:30:00Z');

  it("takes the store's today and the days before it", () => {
    expect(storeToday(now, 'Africa/Maputo')).toBe('2026-11-20');
    expect(storeToday(now, 'UTC')).toBe('2026-11-19');
    expect(lastDays(1, now, 'Africa/Maputo')).toEqual({ from: '2026-11-20', to: '2026-11-20', timeZone: 'Africa/Maputo' });
    expect(dates(lastDays(30, now, 'Africa/Maputo'))).toEqual({ from: '2026-10-22', to: '2026-11-20' });
  });

  it("bounds the period by the store's midnights", () => {
    expect(offsetOn('2026-11-20', 'Africa/Maputo')).toBe('+02:00');
    expect(offsetOn('2026-11-20', 'UTC')).toBe('+00:00');
    expect(offsetOn('2026-07-01', 'Europe/Lisbon')).toBe('+01:00');
    expect(instants({ from: '2026-11-14', to: '2026-11-20', timeZone: 'Africa/Maputo' })).toEqual({
      from: '2026-11-14T00:00:00+02:00',
      to: '2026-11-21T00:00:00+02:00',
    });
  });

  it('formats dates and instants, and never guesses a missing timezone', () => {
    expect(formatBusinessDate('2026-11-19')).toBe('19/11/2026');
    expect(formatBusinessDate(undefined)).toBe('—');
    expect(formatBusinessDate('ontem')).toBe('ontem');
    expect(formatInstant('2026-11-19T18:05:00Z', 'Africa/Maputo')).toBe('19/11/26, 20:05');
    expect(formatInstant('2026-11-19T18:05:00Z', undefined)).toBe('—');
    expect(formatInstant(undefined, 'UTC')).toBe('—');
  });
});
