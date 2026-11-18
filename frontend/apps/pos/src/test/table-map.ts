import type { components } from '@restaurantpro/api-client';
import { SESSION } from './server';

type TableMap = components['schemas']['com.xavier.backend.dining.model.dto.TableMap'];
type Table = components['schemas']['com.xavier.backend.dining.model.dto.TableMap.Table'];

export const id = (n: number) => `6f1c2a9e-0000-4000-8000-${String(n).padStart(12, '0')}`;

/** The open orders of the occupied tables (M2's is `ORDER_ID` of ./order). */
export const M2_ORDER_ID = id(500);
export const M3_ORDER_ID = id(502);
const table = (n: number, code: string, fields: Partial<Table> = {}): Table => ({
  id: id(n),
  code,
  seats: 4,
  status: 'FREE',
  locked: false,
  subtotalPrinted: false,
  reserved: false,
  ...fields,
});

/** Times are UTC; the store is in Africa/Maputo (UTC+2, SESSION), so 10:05Z shows as 12:05 whatever the terminal's zone. */
export const MAP: TableMap = {
  storeId: SESSION.storeId,
  areas: [
    {
      id: id(901),
      code: 'SALA',
      name: 'Sala',
      tables: [
        table(1, 'M1'),
        table(2, 'M2', {
          status: 'OCCUPIED',
          guests: 3,
          openedAt: '2026-11-18T10:05:00Z',
          openedByEmployeeId: id(77),
          openOrderId: M2_ORDER_ID,
        }),
        table(3, 'M3', {
          status: 'OCCUPIED',
          guests: 1,
          openedAt: '2026-11-18T11:40:00Z',
          openOrderId: M3_ORDER_ID,
          locked: true,
          subtotalPrinted: true,
          reserved: true,
          nextReservationAt: '2026-11-18T18:30:00Z',
        }),
        table(4, 'M4', { seats: 1, reserved: true, nextReservationAt: '2026-11-18T19:00:00Z' }),
      ],
    },
    { id: id(902), code: 'ESPLANADA', name: 'Esplanada', tables: [table(10, 'E1', { seats: 2 })] },
  ],
};

/** MAP after another terminal opened M1 for two (its order is `NEW_ORDER_ID` of ./order). */
export const M1_OCCUPIED: TableMap = {
  ...MAP,
  areas: MAP.areas!.map((area) => ({
    ...area,
    tables: area.tables!.map((t) =>
      t.code === 'M1' ? { ...t, status: 'OCCUPIED' as const, guests: 2, openedAt: '2026-11-18T12:00:00Z', openOrderId: id(501) } : t,
    ),
  })),
};
