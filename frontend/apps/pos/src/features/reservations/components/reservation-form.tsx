import { Button, cn, Input, Label } from '@restaurantpro/ui';
import type { ReactNode } from 'react';
import type { Draft } from '../draft';
import { guestsLabel, seatsLabel } from '../labels';
import type { ReservableArea } from '../reservation';
import { addDays } from '../store-time';

/** Stitch's party buttons; any other size is typed. */
const PARTY_SIZES = [1, 2, 3, 4, 5, 6, 8, 10, 12];

type Props = {
  draft: Draft;
  onChange: (draft: Draft) => void;
  /** The store's today, for "Hoje" and "Amanhã". */
  today: string;
  areas: ReservableArea[];
  /** In flight, or waiting for "Tentar novamente": the input is what was sent, so it cannot change. */
  locked: boolean;
  /** The customer lookup, between the tables and the contact. */
  customer?: ReactNode;
};

/**
 * Stitch's "Nova Reserva" steps as plain controlled inputs (the POS has no form library): the day, the time, the
 * party, the tables (any of the store's, several or none: informative only, no capacity check) and the contact.
 */
export function ReservationForm({ draft, onChange, today, areas, locked, customer }: Props) {
  const set = (fields: Partial<Draft>) => onChange({ ...draft, ...fields });
  const toggleTable = (tableId: string) =>
    set({
      tableIds: draft.tableIds.includes(tableId)
        ? draft.tableIds.filter((id) => id !== tableId)
        : [...draft.tableIds, tableId],
    });

  return (
    <div className="flex flex-col gap-6">
      <div className="grid gap-4 sm:grid-cols-2">
        <Step title="1. Data">
          <div className="flex flex-wrap gap-2">
            {[
              { label: 'Hoje', day: today },
              { label: 'Amanhã', day: addDays(today, 1) },
            ].map(({ label, day }) => (
              <Button
                key={label}
                type="button"
                size="touch"
                variant={draft.date === day ? 'default' : 'outline'}
                aria-pressed={draft.date === day}
                disabled={locked}
                onClick={() => set({ date: day })}
              >
                {label}
              </Button>
            ))}
            <Field label="Data" htmlFor="reservation-date">
              <Input
                id="reservation-date"
                type="date"
                value={draft.date}
                readOnly={locked}
                onChange={(event) => set({ date: event.target.value })}
                className="h-12 text-base md:text-base"
              />
            </Field>
          </div>
        </Step>
        <Step title="2. Hora">
          <Field label="Hora" htmlFor="reservation-time">
            <Input
              id="reservation-time"
              type="time"
              value={draft.time}
              readOnly={locked}
              onChange={(event) => set({ time: event.target.value })}
              className="h-12 text-base md:text-base"
            />
          </Field>
        </Step>
      </div>

      <Step title="3. Pessoas">
        <div className="flex flex-wrap gap-2">
          {PARTY_SIZES.map((size) => (
            <Button
              key={size}
              type="button"
              size="touch"
              variant={draft.partySize === String(size) ? 'default' : 'outline'}
              aria-label={guestsLabel(size)}
              aria-pressed={draft.partySize === String(size)}
              disabled={locked}
              onClick={() => set({ partySize: String(size) })}
              className="min-w-14 text-lg tabular-nums"
            >
              {size}
            </Button>
          ))}
          <Field label="Número de pessoas" htmlFor="reservation-party">
            <Input
              id="reservation-party"
              type="number"
              inputMode="numeric"
              min={1}
              max={500}
              value={draft.partySize}
              readOnly={locked}
              onChange={(event) => set({ partySize: event.target.value.replace(/\D/g, '').slice(0, 3) })}
              className="h-12 w-28 text-base md:text-base"
            />
          </Field>
        </div>
      </Step>

      <Step title="4. Mesas (opcional)">
        {areas.length === 0 ? (
          <p className="text-muted-foreground">O mapa de mesas ainda não foi lido. A reserva pode ficar sem mesa.</p>
        ) : (
          areas.map((area) => (
            <fieldset key={area.id} className="flex flex-col gap-2">
              <legend className="mb-2 text-sm font-semibold text-muted-foreground">{area.name}</legend>
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                {area.tables.map((table) => (
                  <label
                    key={table.id}
                    className={cn(
                      'flex min-h-14 cursor-pointer items-center gap-3 rounded-lg border-2 bg-muted px-3 has-focus-visible:ring-2 has-focus-visible:ring-ring',
                      draft.tableIds.includes(table.id) ? 'border-primary' : 'border-border',
                      locked && 'cursor-not-allowed opacity-50',
                    )}
                  >
                    <input
                      type="checkbox"
                      checked={draft.tableIds.includes(table.id)}
                      onChange={() => toggleTable(table.id)}
                      disabled={locked}
                      className="size-5 accent-primary"
                    />
                    <span className="text-lg font-semibold">{table.code}</span>
                    <span className="text-sm text-muted-foreground">{seatsLabel(table.seats)}</span>
                  </label>
                ))}
              </div>
            </fieldset>
          ))
        )}
      </Step>

      <Step title="5. Cliente e contacto">
        {customer}
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Nome" htmlFor="reservation-name">
            <Input
              id="reservation-name"
              value={draft.contactName}
              maxLength={120}
              autoComplete="off"
              readOnly={locked}
              onChange={(event) => set({ contactName: event.target.value })}
              className="h-12 text-base md:text-base"
            />
          </Field>
          <Field label="Telefone" htmlFor="reservation-phone">
            <Input
              id="reservation-phone"
              type="text"
              inputMode="tel"
              value={draft.contactPhone}
              maxLength={40}
              autoComplete="off"
              readOnly={locked}
              onChange={(event) => set({ contactPhone: event.target.value })}
              className="h-12 text-base md:text-base"
            />
          </Field>
        </div>
        <Field label="Notas" htmlFor="reservation-notes">
          <textarea
            id="reservation-notes"
            value={draft.notes}
            maxLength={500}
            rows={2}
            readOnly={locked}
            onChange={(event) => set({ notes: event.target.value })}
            className="rounded-md border border-input bg-muted px-3 py-2 text-base outline-none focus-visible:ring-2 focus-visible:ring-ring"
          />
        </Field>
      </Step>
    </div>
  );
}

function Step({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-3">
      <h3 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">{title}</h3>
      {children}
    </section>
  );
}

function Field({ label, htmlFor, children }: { label: string; htmlFor: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <Label htmlFor={htmlFor}>{label}</Label>
      {children}
    </div>
  );
}
