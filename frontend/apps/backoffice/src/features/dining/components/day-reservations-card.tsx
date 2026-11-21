import { ApiError } from '@restaurantpro/api-client';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  DataState,
  Input,
  Label,
  StatusBadge,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  type StatusMap,
} from '@restaurantpro/ui';
import { Ban, CalendarClock, CircleHelp, Lock, UserCheck, UserX } from 'lucide-react';
import { useId, useState } from 'react';
import { queryState, useStoreTimeZone } from '../../organization';
import { dateIn, timeIn } from '../dates';
import { useDayReservations } from '../hooks/use-dining-settings';
import { useTables } from '../hooks/use-floor';
import type { ReservationView } from '../types';

/** `ReservationView.status` (§8.3); an unknown one shows as itself. */
const statuses: StatusMap<string> = {
  BOOKED: { label: 'Reservada', icon: CalendarClock, tone: 'info' },
  SEATED: { label: 'Sentada', icon: UserCheck, tone: 'success' },
  CANCELLED: { label: 'Cancelada', icon: Ban, tone: 'neutral' },
  NO_SHOW: { label: 'Não compareceu', icon: UserX, tone: 'warning' },
};

function statusesOf(status: string): StatusMap<string> {
  return status in statuses ? statuses : { [status]: { label: status, icon: CircleHelp, tone: 'neutral' } };
}

/**
 * One day's reservations of the store, read-only (they are taken and changed on the POS). The day starts as today in
 * the store's timezone; times show in that timezone too (FC §12).
 */
export function DayReservationsCard({ storeId }: { storeId: string }) {
  const timeZone = useStoreTimeZone(storeId);
  const [picked, setPicked] = useState<string>();
  const date = picked ?? (timeZone ? dateIn(timeZone, new Date()) : '');
  const reservations = useDayReservations(storeId, date);
  const tables = useTables(storeId);
  const titleId = useId();
  const dayId = useId();
  const codes = new Map((tables.data?.items ?? []).map((table) => [table.id, table.code]));

  return (
    <Card role="region" aria-labelledby={titleId} className="gap-4">
      <CardHeader className="flex flex-row flex-wrap items-end justify-between gap-3">
        <div className="flex flex-col gap-1">
          <CardTitle id={titleId} className="text-lg">
            Reservas do dia
          </CardTitle>
          <CardDescription>As reservas são feitas e alteradas no POS.</CardDescription>
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor={dayId}>Dia</Label>
          <Input id={dayId} type="date" value={date} onChange={(event) => setPicked(event.target.value)} className="w-44" />
        </div>
      </CardHeader>
      <CardContent>
        {reservations.error instanceof ApiError && reservations.error.status === 403 ? (
          <p className="flex items-center gap-2 py-6 text-muted-foreground">
            <Lock aria-hidden="true" className="size-5" />
            Sem permissão para ver as reservas desta loja.
          </p>
        ) : (
          <DataState
            state={queryState(reservations.error, reservations.data)}
            onRetry={() => void reservations.refetch()}
            emptyMessage="Sem reservas neste dia."
          >
            <Table>
              <TableCaption className="sr-only">Reservas do dia</TableCaption>
              <TableHeader>
                <TableRow>
                  <TableHead>Hora</TableHead>
                  <TableHead>Nome</TableHead>
                  <TableHead>Pessoas</TableHead>
                  <TableHead>Mesas</TableHead>
                  <TableHead>Estado</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {reservations.data?.map((reservation) => (
                  <ReservationRow key={reservation.id} reservation={reservation} timeZone={timeZone} codes={codes} />
                ))}
              </TableBody>
            </Table>
          </DataState>
        )}
      </CardContent>
    </Card>
  );
}

type RowProps = { reservation: ReservationView; timeZone: string | undefined; codes: Map<string | undefined, string | undefined> };

function ReservationRow({ reservation, timeZone, codes }: RowProps) {
  const status = reservation.status ?? '';
  return (
    <TableRow>
      <TableCell className="font-semibold">{reservation.startsAt && timeZone ? timeIn(reservation.startsAt, timeZone) : '—'}</TableCell>
      <TableCell>{reservation.contactName}</TableCell>
      <TableCell>{reservation.partySize}</TableCell>
      <TableCell className="font-mono">{(reservation.tableIds ?? []).map((id) => codes.get(id) ?? '?').join(', ')}</TableCell>
      <TableCell>
        <StatusBadge status={status} statuses={statusesOf(status)} />
      </TableCell>
    </TableRow>
  );
}
