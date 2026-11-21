import { ApiError } from '@restaurantpro/api-client';
import { Button, Skeleton } from '@restaurantpro/ui';
import { Armchair, CalendarPlus } from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { useStoreTimeZone } from '../../../lib/auth/use-employee';
import { errorMessage, isOffline } from '../../../lib/error-messages';
import { useCan } from '../hooks/use-can';
import { useReservations } from '../hooks/use-reservations';
import { useStoreToday } from '../hooks/use-store-today';
import { matches, type ReservableArea, type Reservation, type StatusFilter, summarize, tableCodes } from '../reservation';
import { storeClock } from '../store-time';
import { CancelDialog, NoShowDialog } from './close-dialogs';
import { DayBar } from './day-bar';
import { DaySummary } from './day-summary';
import { ListFilter } from './list-filter';
import { ReservationCard } from './reservation-card';
import { BookedActions, ReservationDetail } from './reservation-detail';
import { EditReservationDialog, NewReservationDialog } from './reservation-dialog';
import { SeatDialog } from './seat-dialog';

type Props = {
  /** The store's table map, for the tables' codes; empty until it is read. */
  areas: ReservableArea[];
};

/** `/reservations` (§4, Stitch "Gestão de Reservas"): the store's reservations of one day. */
export function ReservationsScreen({ areas }: Props) {
  const timeZone = useStoreTimeZone();
  const today = useStoreToday(timeZone);
  const [date, setDate] = useState(today);
  const day = useReservations(date);
  const navigate = useNavigate();
  const [command, setCommand] = useState<Command>();
  const [booking, setBooking] = useState(false);
  const canManage = useCan('RESERVATION_MANAGE');
  const target = day.data?.find((reservation) => reservation.id === command?.id);
  const close = () => setCommand(undefined);

  return (
    <div className="flex flex-col gap-4">
      <h2 className="sr-only">Reservas</h2>
      <div className="flex flex-wrap items-center justify-between gap-4 rounded-xl border bg-card p-4">
        <DayBar date={date} today={today} onDate={setDate} />
        {canManage && (
          <Button size="touch-primary" onClick={() => setBooking(true)}>
            <CalendarPlus aria-hidden="true" />
            Nova reserva
          </Button>
        )}
      </div>
      {booking && <NewReservationDialog date={date} today={today} areas={areas} onClose={() => setBooking(false)} />}
      {command && target && (
        <CommandOn
          kind={command.kind}
          reservation={target}
          today={today}
          areas={areas}
          onClose={close}
          onSeated={(orderId) => navigate(`/orders/${orderId}`)}
        />
      )}
      {day.data ? (
        <Day reservations={day.data} areas={areas} timeZone={timeZone} onCommand={canManage ? setCommand : undefined} />
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

/** The dialog open over the day: a command on one reservation, named by id so it always shows the current one. */
type Command = { kind: 'seat' | 'edit' | 'no-show' | 'cancel'; id: string };

type CommandProps = {
  kind: Command['kind'];
  reservation: Reservation;
  today: string;
  areas: ReservableArea[];
  onClose: () => void;
  onSeated: (orderId: string) => void;
};

function CommandOn({ kind, reservation, today, areas, onClose, onSeated }: CommandProps) {
  switch (kind) {
    case 'seat':
      return <SeatDialog reservation={reservation} areas={areas} onClose={onClose} onSeated={onSeated} />;
    case 'cancel':
      return <CancelDialog reservation={reservation} onClose={onClose} />;
    case 'no-show':
      return <NoShowDialog reservation={reservation} onClose={onClose} />;
    case 'edit':
      return <EditReservationDialog reservation={reservation} today={today} areas={areas} onClose={onClose} />;
  }
}

type DayProps = {
  reservations: Reservation[];
  areas: ReservableArea[];
  timeZone: string;
  /** None for who may not manage reservations: the day is shown without its commands. */
  onCommand: ((command: Command) => void) | undefined;
};

function Day({ reservations, areas, timeZone, onCommand }: DayProps) {
  const [status, setStatus] = useState<StatusFilter>('ALL');
  const [query, setQuery] = useState('');
  // The id only: the panel shows the reservation as the day has it now, and nothing once it is gone.
  const [selectedId, setSelectedId] = useState<string>();
  if (reservations.length === 0) return <p className="py-6 text-center text-muted-foreground">Não há reservas neste dia.</p>;
  const summary = summarize(reservations);
  const shown = reservations.filter((reservation) => matches(reservation, { status, query }));
  const selected = reservations.find((reservation) => reservation.id === selectedId);
  const time = (reservation: Reservation) => storeClock(reservation.startsAt, timeZone).time;
  return (
    <>
      <DaySummary summary={summary} />
      <div className="flex flex-col gap-4 lg:flex-row lg:items-start">
        <div className="flex min-w-0 flex-1 flex-col gap-3">
          <ListFilter summary={summary} status={status} onStatus={setStatus} query={query} onQuery={setQuery} />
          {shown.length === 0 ? (
            <p className="py-6 text-center text-muted-foreground">Nenhuma reserva corresponde à pesquisa.</p>
          ) : (
            <ul className="flex flex-col gap-3">
              {shown.map((reservation) => (
                <li key={reservation.id}>
                  <ReservationCard
                    reservation={reservation}
                    time={time(reservation)}
                    tables={tableCodes(reservation.tableIds, areas)}
                    selected={reservation.id === selectedId}
                    onSelect={() => setSelectedId(reservation.id)}
                  >
                    {onCommand && reservation.status === 'BOOKED' && (
                      <Button size="touch" onClick={() => onCommand({ kind: 'seat', id: reservation.id })}>
                        <Armchair aria-hidden="true" />
                        Sentar
                      </Button>
                    )}
                  </ReservationCard>
                </li>
              ))}
            </ul>
          )}
        </div>
        <ReservationDetail
          reservation={selected}
          time={selected ? time(selected) : ''}
          tables={selected ? tableCodes(selected.tableIds, areas) : ''}
          actions={
            selected &&
            onCommand && (
              <BookedActions
                onSeat={() => onCommand({ kind: 'seat', id: selected.id })}
                onEdit={() => onCommand({ kind: 'edit', id: selected.id })}
                onNoShow={() => onCommand({ kind: 'no-show', id: selected.id })}
                onCancel={() => onCommand({ kind: 'cancel', id: selected.id })}
              />
            )
          }
        />
      </div>
    </>
  );
}

function loadError(error: Error): string {
  if (!(error instanceof ApiError)) return 'Não foi possível carregar as reservas. A resposta do servidor está incompleta.';
  if (isOffline(error)) return 'Sem ligação — não foi possível carregar as reservas.';
  return `Não foi possível carregar as reservas. ${errorMessage(error)}`;
}
