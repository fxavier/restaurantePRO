import { Button } from '@restaurantpro/ui';
import { Armchair, Ban, Clock, NotebookPen, Pencil, Phone, UserX, Users } from 'lucide-react';
import type { ReactNode } from 'react';
import { StatusChip } from '../../../lib/state-tint';
import { guestsLabel } from '../labels';
import type { Reservation } from '../reservation';
import { STATUSES } from './reservation-card';

type Props = {
  reservation: Reservation | undefined;
  time: string;
  tables: string;
  /** The commands of a booked reservation, shown only to who may manage them (the backend enforces). */
  actions: ReactNode;
};

/** The light reference's right card: the selected reservation in full, and what can be done with it. */
export function ReservationDetail({ reservation, time, tables, actions }: Props) {
  return (
    <aside aria-label="Detalhe da reserva" className="flex flex-col gap-4 rounded-2xl border bg-card p-4 lg:w-96 lg:shrink-0">
      {reservation ? (
        <>
          <div className="flex flex-wrap items-start justify-between gap-2">
            <h3 className="text-2xl font-semibold">{reservation.contactName}</h3>
            <StatusChip status={reservation.status} statuses={STATUSES} />
          </div>
          <dl className="flex flex-col gap-2">
            <Fact icon={<Clock aria-hidden="true" className="size-5" />} term="Hora">
              <span className="tabular-nums">{time}</span>
            </Fact>
            <Fact icon={<Users aria-hidden="true" className="size-5" />} term="Pessoas">
              {guestsLabel(reservation.partySize)}
            </Fact>
            <Fact icon={<Armchair aria-hidden="true" className="size-5" />} term="Mesas">
              {tables || 'Sem mesa atribuída'}
            </Fact>
            {reservation.phone && (
              <Fact icon={<Phone aria-hidden="true" className="size-5" />} term="Telefone">
                <span className="tabular-nums">{reservation.phone}</span>
              </Fact>
            )}
            {reservation.notes && (
              <Fact icon={<NotebookPen aria-hidden="true" className="size-5" />} term="Notas">
                {reservation.notes}
              </Fact>
            )}
            {reservation.cancelReason && (
              <Fact icon={<Ban aria-hidden="true" className="size-5" />} term="Motivo do cancelamento">
                {reservation.cancelReason}
              </Fact>
            )}
          </dl>
          {reservation.status === 'BOOKED' && actions}
        </>
      ) : (
        <p className="py-6 text-center text-muted-foreground">Escolha uma reserva para ver o detalhe.</p>
      )}
    </aside>
  );
}

function Fact({ icon, term, children }: { icon: ReactNode; term: string; children: ReactNode }) {
  return (
    <div className="flex items-start gap-3">
      <dt className="flex w-40 shrink-0 items-center gap-2 text-sm text-muted-foreground">
        {icon}
        {term}
      </dt>
      <dd className="font-medium">{children}</dd>
    </div>
  );
}

type ActionsProps = {
  onSeat: () => void;
  onEdit: () => void;
  onNoShow: () => void;
  onCancel: () => void;
};

/** A booked reservation's four commands (§8.3): seat it, change it, or close it as a no-show or a cancellation. */
export function BookedActions({ onSeat, onEdit, onNoShow, onCancel }: ActionsProps) {
  return (
    <div className="flex flex-col gap-2">
      <Button size="touch-primary" className="rounded-xl" onClick={onSeat}>
        <Armchair aria-hidden="true" />
        Sentar
      </Button>
      <Button variant="outline" size="touch" className="rounded-xl bg-card" onClick={onEdit}>
        <Pencil aria-hidden="true" />
        Editar reserva
      </Button>
      <Button variant="destructive" size="touch" className="rounded-xl" onClick={onNoShow}>
        <UserX aria-hidden="true" />
        Registar não comparência
      </Button>
      <Button variant="destructive" size="touch" className="rounded-xl" onClick={onCancel}>
        <Ban aria-hidden="true" />
        Cancelar reserva
      </Button>
    </div>
  );
}
