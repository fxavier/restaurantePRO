import { ApiError } from '@restaurantpro/api-client';
import { Button, Skeleton } from '@restaurantpro/ui';
import { useState } from 'react';
import { useStoreTimeZone } from '../../../lib/auth/use-employee';
import { errorMessage, isOffline } from '../../../lib/error-messages';
import { useReservations } from '../hooks/use-reservations';
import { type ReservableArea, type Reservation, tableCodes } from '../reservation';
import { dayTitle, storeClock, storeDate } from '../store-time';
import { ReservationCard } from './reservation-card';

type Props = {
  /** The store's table map, for the tables' codes; empty until it is read. */
  areas: ReservableArea[];
};

/** `/reservations` (§4, Stitch "Gestão de Reservas"): the store's reservations of one day. */
export function ReservationsScreen({ areas }: Props) {
  const timeZone = useStoreTimeZone();
  const [date] = useState(() => storeDate(new Date(), timeZone));
  const day = useReservations(date);

  return (
    <div className="flex flex-col gap-4">
      <h2 className="sr-only">Reservas</h2>
      <h3 className="text-2xl font-semibold">{dayTitle(date)}</h3>
      {day.data ? (
        <DayList reservations={day.data} areas={areas} timeZone={timeZone} />
      ) : day.isError ? (
        <div className="flex flex-col items-center gap-3 py-6 text-center">
          <p role="alert">{loadError(day.error)}</p>
          <Button variant="outline" size="touch" onClick={() => void day.refetch()}>
            Tentar novamente
          </Button>
        </div>
      ) : (
        <div role="status" aria-label="A carregar as reservas…" className="flex flex-col gap-3">
          {Array.from({ length: 4 }, (_, index) => (
            <Skeleton key={index} className="h-28 rounded-xl" />
          ))}
        </div>
      )}
    </div>
  );
}

function DayList({ reservations, areas, timeZone }: { reservations: Reservation[]; areas: ReservableArea[]; timeZone: string }) {
  if (reservations.length === 0) return <p className="py-6 text-center text-muted-foreground">Não há reservas neste dia.</p>;
  return (
    <ul className="flex flex-col gap-3">
      {reservations.map((reservation) => (
        <li key={reservation.id}>
          <ReservationCard
            reservation={reservation}
            time={storeClock(reservation.startsAt, timeZone).time}
            tables={tableCodes(reservation.tableIds, areas)}
          />
        </li>
      ))}
    </ul>
  );
}

function loadError(error: Error): string {
  if (!(error instanceof ApiError)) return 'Não foi possível carregar as reservas. A resposta do servidor está incompleta.';
  if (isOffline(error)) return 'Sem ligação — não foi possível carregar as reservas.';
  return `Não foi possível carregar as reservas. ${errorMessage(error)}`;
}
