import type { components } from '@restaurantpro/api-client';
import { HttpResponse } from 'msw';
import { queue, recording } from './server';
import { id } from './table-map';

export type CourierView = components['schemas']['com.xavier.backend.orders.delivery.model.dto.CourierView'];

/** The store's couriers: two who ride, neither of them Ana, who runs the board. */
export const COURIER = {
  joao: { id: id(1401), code: 'E02', name: 'João Pereira' },
  rita: { id: id(1402), code: 'E05', name: 'Rita Mabunda' },
} satisfies Record<string, CourierView>;

/** `GET /api/v1/pos/couriers`: by default, both couriers. */
export const couriersEndpoint = (...answers: (() => Response | Promise<Response>)[]) =>
  recording<undefined>(
    'get',
    '/api/v1/pos/couriers',
    queue(...(answers.length ? answers : [() => HttpResponse.json(Object.values(COURIER))])),
  );
