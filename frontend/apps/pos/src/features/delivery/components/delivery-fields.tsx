import { Button, Skeleton } from '@restaurantpro/ui';
import { Clock, MapPin, MapPinned, UserRound } from 'lucide-react';
import { type ReactNode, useId } from 'react';
import type { DeliveryDraft } from '../delivery';
import { type CustomerCreation, useCustomer } from '../hooks/use-customers';
import { useDeliveryZones } from '../hooks/use-delivery-zones';
import { loadFailure } from '../messages';
import { AddressPicker } from './address-picker';
import { type Choice, ChoiceGroup } from './choice-group';
import { CustomerPicker } from './customer-picker';

type PromiseChoice = 'now' | '20' | '30' | '45' | '60';

const PROMISES: readonly Choice<PromiseChoice>[] = [
  { value: 'now', label: 'Imediato' },
  ...(['20', '30', '45', '60'] as const).map((minutes) => ({ value: minutes, label: `${minutes} min` })),
];

/** DeliveryRequest: notes of at most 2000 characters. */
const MAX_NOTES = 2000;

type Props = { draft: DeliveryDraft; onChange: (draft: DeliveryDraft) => void; disabled: boolean };

/** When the customer is promised the delivery (Stitch: under the channel): as soon as possible, or in some minutes. */
export function PromiseField({ draft, onChange, disabled }: Props) {
  return (
    <ChoiceGroup
      legend={
        <>
          <Clock aria-hidden="true" className="size-4" />
          2. Hora prometida
        </>
      }
      name="promise"
      choices={PROMISES}
      value={draft.promiseMinutes === null ? 'now' : (String(draft.promiseMinutes) as PromiseChoice)}
      onChange={(choice) => onChange({ ...draft, promiseMinutes: choice === 'now' ? null : Number(choice) })}
      disabled={disabled}
      className="grid-cols-2"
    />
  );
}

/**
 * What else a delivery opens with (D127), in Stitch's second and third columns: the customer and the address; the
 * zone and the notes for the courier.
 */
export function DeliveryFields({
  draft,
  onChange,
  disabled,
  createCustomer,
}: Props & { createCustomer: CustomerCreation }) {
  const customer = useCustomer(draft.customerId);
  const set = (change: Partial<DeliveryDraft>) => onChange({ ...draft, ...change });
  const notesId = useId();
  return (
    <>
      <div className="flex min-w-0 flex-col gap-4">
        <Section icon={UserRound} title="3. Cliente">
          <CustomerPicker
            customerId={draft.customerId}
            onChange={(customerId) => set({ customerId, address: undefined })}
            disabled={disabled}
            create={createCustomer}
          />
        </Section>
        {customer.data && (
          <Section icon={MapPin} title="4. Morada de entrega">
            <AddressPicker
              addresses={customer.data.addresses ?? []}
              value={draft.address}
              onChange={(address) => set({ address })}
              disabled={disabled}
            />
          </Section>
        )}
      </div>
      <div className="flex min-w-0 flex-col gap-4">
        <Zones zoneId={draft.zoneId} onChange={(zoneId) => set({ zoneId })} disabled={disabled} />
        <div className="flex flex-col gap-1">
          <label htmlFor={notesId} className="text-sm font-medium">
            Notas para o estafeta
          </label>
          <textarea
            id={notesId}
            value={draft.notes}
            maxLength={MAX_NOTES}
            rows={2}
            readOnly={disabled}
            onChange={(event) => set({ notes: event.target.value })}
            className="min-h-14 rounded-xl border border-input bg-card px-3 py-2 text-base focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
          />
        </div>
      </div>
    </>
  );
}

function Zones({ zoneId, onChange, disabled }: { zoneId?: string; onChange: (zoneId: string) => void; disabled: boolean }) {
  const zones = useDeliveryZones();
  const legend = (
    <>
      <MapPinned aria-hidden="true" className="size-4" />
      5. Zona de entrega
    </>
  );
  if (zones.data) {
    const choices = zones.data.flatMap(({ id, name, code }) => (id ? [{ value: id, label: name ?? '', hint: `Zona ${code}` }] : []));
    if (choices.length === 0) {
      return <p className="text-muted-foreground">Esta loja não tem zonas de entrega ativas. Um gestor cria-as no BackOffice.</p>;
    }
    return <ChoiceGroup legend={legend} name="zone" choices={choices} value={zoneId} onChange={onChange} disabled={disabled} />;
  }
  if (zones.isError) {
    return (
      <div className="flex flex-col items-start gap-2">
        <p role="alert" className="text-error">
          {loadFailure(zones.error, 'as zonas de entrega')}
        </p>
        <Button type="button" variant="outline" size="touch" onClick={() => void zones.refetch()}>
          Tentar novamente
        </Button>
      </div>
    );
  }
  return <Skeleton role="status" aria-label="A carregar as zonas de entrega…" className="h-28" />;
}

function Section({ icon: Icon, title, children }: { icon: typeof UserRound; title: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="flex flex-col gap-2">
      <h3 className="flex items-center gap-2 text-sm font-semibold text-muted-foreground">
        <Icon aria-hidden="true" className="size-4" />
        {title}
      </h3>
      {children}
    </section>
  );
}
