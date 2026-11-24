import type { components } from '@restaurantpro/api-client';
import { HttpResponse } from 'msw';
import { queue, recording } from './server';

type ProductionAlertsView = components['schemas']['com.xavier.backend.production.model.dto.ProductionAlertsView'];
type SettlementAlertsView = components['schemas']['com.xavier.backend.settlement.model.dto.SettlementAlertsView'];
type Answer = () => Response | Promise<Response>;

/** `GET /api/v1/pos/production/alerts`, answering from a queue whose last answer repeats. */
export const productionAlertsEndpoint = (...answers: Answer[]) =>
  recording<undefined>('get', '/api/v1/pos/production/alerts', queue(...answers));

/** `GET /api/v1/pos/settlement/alerts`, answering from a queue whose last answer repeats. */
export const settlementAlertsEndpoint = (...answers: Answer[]) =>
  recording<undefined>('get', '/api/v1/pos/settlement/alerts', queue(...answers));

/** Production's answer: nothing pending, the 15-minute kitchen delay, unless `body` says otherwise. */
export const productionAlerts = (body: Partial<ProductionAlertsView> = {}) => () =>
  HttpResponse.json({ pendingPrintJobs: 0, delayedKitchenRounds: 0, kitchenDelayMinutes: 15, ...body });

/** Settlement's answer: nothing in doubt, unless `body` says otherwise. */
export const settlementAlerts = (body: Partial<SettlementAlertsView> = {}) => () =>
  HttpResponse.json({ paymentsToConfirm: 0, oldestOrderId: null, ...body });
