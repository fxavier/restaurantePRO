import type { components } from '@restaurantpro/api-client';
import { act } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { renderPos } from './render-pos';
import { problem, queue, recording, SESSION, token, tokenEndpoint } from './server';

type Schemas = components['schemas'];
export type SessionDetails = Schemas['com.xavier.backend.cash.model.dto.SessionDetails'];
export type SessionView = Schemas['com.xavier.backend.cash.SessionView'];
export type RegisterPage = Schemas['com.xavier.backend.shared.web.PageResponseCom.xavier.backend.cash.model.dto.RegisterSummary'];
export type CurrentDayView = Schemas['com.xavier.backend.cash.model.dto.CurrentDayView'];
export type DayView = Schemas['com.xavier.backend.cash.DayView'];
export type ClosingView = Schemas['com.xavier.backend.cash.model.dto.ClosingView'];
export type MovementView = Schemas['com.xavier.backend.cash.MovementView'];
export type PrintJobView = Schemas['com.xavier.backend.production.model.dto.PrintJobView'];
export type OpenSessionRequest = Schemas['com.xavier.backend.cash.model.dto.OpenSessionRequest'];
export type ManualMovementRequest = Schemas['com.xavier.backend.cash.model.dto.ManualMovementRequest'];
export type CloseSessionRequest = Schemas['com.xavier.backend.settlement.model.dto.CloseSessionRequest'];
export type CreateClosingRequest = Schemas['com.xavier.backend.cash.model.dto.CreateClosingRequest'];
export type JustifyClosingRequest = Schemas['com.xavier.backend.cash.model.dto.JustifyClosingRequest'];
export type CloseDayRequest = Schemas['com.xavier.backend.settlement.model.dto.CloseDayRequest'];
export type DrawerKickRequest = Schemas['com.xavier.backend.production.model.dto.DrawerKickRequest'];

const cid = (n: number) => `6f1c2a9e-0000-4000-8000-${String(n).padStart(12, '0')}`;
const STORE = SESSION.storeId;

export const REGISTER = { main: cid(801), bar: cid(802), old: cid(803) };
export const DAY_ID = cid(810);
export const SESSION_ID = cid(820);
export const CLOSING_ID = cid(830);

export const REGISTERS: RegisterPage = {
  content: [
    { id: REGISTER.main, code: 'C01', name: 'Caixa Principal', storeId: STORE, active: true, version: 1 },
    { id: REGISTER.bar, code: 'C02', name: 'Caixa Bar', storeId: STORE, active: true, version: 1 },
    { id: REGISTER.old, code: 'C09', name: 'Caixa Antiga', storeId: STORE, active: false, version: 3 },
  ],
  page: 0,
  size: 100,
  totalElements: 3,
};

export const DAY: CurrentDayView = {
  id: DAY_ID,
  storeId: STORE,
  businessDate: '2026-11-19',
  status: 'OPEN',
  version: 4,
  earlierOpenDays: [],
};

/**
 * Ana's open session on the main register: opened 06:30Z (08:30 in the store, Africa/Maputo), a float of 1 500,00 MZN,
 * and what the session expects per method, which a blind count never shows.
 */
export const OPEN_SESSION: SessionDetails = {
  id: SESSION_ID,
  storeId: STORE,
  registerId: REGISTER.main,
  cashDayId: DAY_ID,
  businessDate: '2026-11-19',
  operatorEmployeeId: SESSION.employeeId,
  status: 'OPEN',
  openedAt: '2026-11-19T06:30:00Z',
  openingFloat: { amount: '1500.00', currency: 'MZN' },
  expected: [
    { methodCode: 'CARD', amount: { amount: '3210.40', currency: 'MZN' } },
    { methodCode: 'CASH', amount: { amount: '4875.50', currency: 'MZN' } },
  ],
  warnings: [],
  version: 2,
};

export const CLOSED_SESSION: SessionView = {
  id: SESSION_ID,
  storeId: STORE,
  registerId: REGISTER.main,
  cashDayId: DAY_ID,
  operatorEmployeeId: SESSION.employeeId,
  status: 'CLOSED',
  version: 3,
};

const money = (amount: string) => ({ amount, currency: 'MZN' });

/** The main register's closing: card exact, cash 25,00 short. */
export const CLOSING: ClosingView = {
  id: CLOSING_ID,
  storeId: STORE,
  registerId: REGISTER.main,
  cashDayId: DAY_ID,
  dayRound: 1,
  attemptNo: 1,
  status: 'DRAFT',
  version: 0,
  lines: [
    { methodCode: 'CASH', expected: money('4875.50'), counted: money('4850.50'), difference: money('-25.00') },
    { methodCode: 'CARD', expected: money('3210.40'), counted: money('3210.40'), difference: money('0.00') },
  ],
};

type Answer<Body = undefined> = (body: Body) => Response | Promise<Response>;

export const json = (body: object, status = 200) => () => HttpResponse.json(body, { status });
/** What `GET /cash/sessions/current` answers when the employee has no open session. */
export const noOpenSession = () => problem(404, 'NOT_FOUND');
const BASE = '/api/v1/pos/cash';

export const currentSessionEndpoint = (...answers: Answer[]) =>
  recording<undefined>('get', `${BASE}/sessions/current`, queue(...answers));
export const registersEndpoint = (...answers: Answer[]) =>
  recording<undefined>('get', `${BASE}/registers`, queue(...(answers.length ? answers : [json(REGISTERS)])));
export const currentDayEndpoint = (...answers: Answer[]) =>
  recording<undefined>('get', `${BASE}/days/current`, queue(...(answers.length ? answers : [json(DAY)])));
export const openSessionEndpoint = (...answers: Answer<OpenSessionRequest>[]) =>
  recording<OpenSessionRequest>('post', `${BASE}/sessions`, queue(...answers));
export const movementEndpoint = (...answers: Answer<ManualMovementRequest>[]) =>
  recording<ManualMovementRequest>('post', `${BASE}/movements`, queue(...answers));
export const closeSessionEndpoint = (...answers: Answer<CloseSessionRequest>[]) =>
  recording<CloseSessionRequest>('post', `${BASE}/sessions/${SESSION_ID}/close`, queue(...answers));
export const createClosingEndpoint = (...answers: Answer<CreateClosingRequest>[]) =>
  recording<CreateClosingRequest>('post', `${BASE}/closings`, queue(...answers));
export const justifyClosingEndpoint = (...answers: Answer<JustifyClosingRequest>[]) =>
  recording<JustifyClosingRequest>('put', `${BASE}/closings/${CLOSING_ID}`, queue(...answers));
export const submitClosingEndpoint = (...answers: Answer[]) =>
  recording<undefined>('post', `${BASE}/closings/${CLOSING_ID}/submit`, queue(...answers));
export const closeDayEndpoint = (...answers: Answer<CloseDayRequest>[]) =>
  recording<CloseDayRequest>('post', `${BASE}/days/${DAY_ID}/operational-close`, queue(...answers));
export const drawerKickEndpoint = (...answers: Answer<DrawerKickRequest>[]) =>
  recording<DrawerKickRequest>('post', '/api/v1/pos/drawer-kicks', queue(...answers));

/** The cash permissions a cashier holds in the terminal's store; `extra` adds e.g. `CASH_DAY_CLOSE`. */
export const cashPermissions = (...names: string[]) =>
  ['CASH_OPERATE', 'CASH_VIEW', ...names].map((name) => `PERM_${name}@${STORE}`);

type AtCash = {
  permissions?: string[];
  /** `GET /cash/sessions/current`: Ana's open session unless the test says otherwise. */
  session?: Answer[];
  day?: Answer[];
  registers?: Answer[];
};

/** Logged in as Ana with `permissions`, at `/cash`, with the session, the day and the registers stood in. */
export async function atCash({
  permissions = cashPermissions(),
  session = [json(OPEN_SESSION)],
  day = [json(DAY)],
  registers = [json(REGISTERS)],
}: AtCash = {}) {
  tokenEndpoint(token('jwt-1'));
  const sessions = currentSessionEndpoint(...session);
  const days = currentDayEndpoint(...day);
  registersEndpoint(...registers);
  const rendered = await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables' });
  act(() => rendered.services.session.start({ ...SESSION, permissions }));
  await act(() => rendered.router.navigate('/cash'));
  return { ...rendered, sessions, days };
}
