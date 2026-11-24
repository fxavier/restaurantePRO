import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@restaurantpro/ui';
import { Bike, ShoppingBag, Store } from 'lucide-react';
import { type FormEvent, useState } from 'react';
import { useEmployee } from '../../../lib/auth/use-employee';
import { useCommandsBlocked } from '../../../lib/live/use-connection';
import { deliveryOrder, type DeliveryDraft, intentState, mayRunDeliveries } from '../delivery';
import { useCreateCustomer } from '../hooks/use-customers';
import { useOpenOrder } from '../hooks/use-open-order';
import { ORDER_UNSETTLED } from '../messages';
import { type Choice, ChoiceGroup } from './choice-group';
import { CommandMessage } from './command-message';
import { DeliveryFields, PromiseField } from './delivery-fields';

type Channel = 'TAKEAWAY' | 'DELIVERY';

const CHANNELS: readonly Choice<Channel>[] = [
  { value: 'TAKEAWAY', label: 'Takeaway', hint: 'Levantamento na loja', icon: ShoppingBag },
  { value: 'DELIVERY', label: 'Delivery', hint: 'Entrega ao domicílio', icon: Bike },
];

const SUBMIT: Record<Channel, string> = { TAKEAWAY: 'Abrir takeaway', DELIVERY: 'Criar entrega' };
const TAKEAWAY = { mode: 'TAKEAWAY' } as const;
const EMPTY: DeliveryDraft = { promiseMinutes: null, notes: '' };

type Props = { onClose: () => void; onOpened: (orderId: string) => void };

/**
 * A new order outside the dining room (§4, Stitch "Novo Pedido Fora de Sala"): a takeaway opens at once; a delivery
 * first names its customer, address, zone and promised time. Either way the items are added on the order's screen.
 */
export function NewOrderDialog({ onClose, onOpened }: Props) {
  const offered = useOfferedChannels();
  const { channel, setChannel, draft, setDraft, blocked, open, awaitingRetry, locked, ready, submit } = useNewOrder(onOpened);
  // Owned here, not by the picker: whether its "Criar cliente" is unsettled decides what this dialog allows, in the
  // same render that offers its "Tentar novamente".
  const createCustomer = useCreateCustomer({
    onCreated: (customerId) => setDraft((current) => ({ ...current, customerId, address: undefined })),
  });
  const delivery = channel === 'DELIVERY';
  // An intent in flight, or without an answer, may already have happened: closing, or leaving the delivery, would lose
  // its key and its "Tentar novamente" (FC §8).
  const held = locked || intentState(createCustomer).locked;

  return (
    <Dialog open onOpenChange={(next) => !next && !held && onClose()}>
      <DialogContent showCloseButton={false} className="max-h-screen overflow-y-auto rounded-2xl sm:max-w-5xl">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle className="text-xl">Novo pedido fora de sala</DialogTitle>
            <DialogDescription>Escolha o canal; os artigos registam-se a seguir, no ecrã do pedido.</DialogDescription>
          </DialogHeader>
          <div className="grid gap-6 lg:grid-cols-3">
            <div className="flex min-w-0 flex-col gap-6">
              <ChoiceGroup legend="1. Canal de venda" name="channel" choices={offered} value={channel} onChange={setChannel} disabled={held} />
              {delivery && <PromiseField draft={draft} onChange={setDraft} disabled={locked} />}
            </div>
            {delivery ? (
              <DeliveryFields draft={draft} onChange={setDraft} disabled={locked} createCustomer={createCustomer} />
            ) : (
              <TakeawayNote />
            )}
          </div>
          <CommandMessage blocked={blocked} error={open.error} what="a abertura do pedido" />
          {awaitingRetry && <p className="text-muted-foreground">{ORDER_UNSETTLED}</p>}
          <Footer
            pending={open.pending}
            canCancel={!held}
            canSubmit={!blocked && ready}
            label={awaitingRetry ? 'Tentar novamente' : SUBMIT[channel]}
            onCancel={onClose}
          />
        </form>
      </DialogContent>
    </Dialog>
  );
}

/** Delivery only for an employee who may run deliveries here; takeaway for anyone. */
function useOfferedChannels() {
  const employee = useEmployee();
  return mayRunDeliveries(employee) ? CHANNELS : CHANNELS.filter(({ value }) => value === 'TAKEAWAY');
}

/** The dialog's draft and its one "open" intent: a retry resends that intent, never a new one. */
function useNewOrder(onOpened: (orderId: string) => void) {
  const [channel, setChannel] = useState<Channel>('TAKEAWAY');
  const [draft, setDraft] = useState<DeliveryDraft>(EMPTY);
  const blocked = useCommandsBlocked();
  const open = useOpenOrder({ onOpened });
  const { awaitingRetry, locked } = intentState(open);
  /** The order to open, or nothing while a delivery misses a detail; a promise counts from `now`, the tap. */
  const order = (now: Date) => (channel === 'DELIVERY' ? deliveryOrder(draft, now) : TAKEAWAY);
  const ready = order(new Date()) !== undefined;

  function submit(event: FormEvent) {
    event.preventDefault();
    if (blocked) return;
    if (awaitingRetry) return open.retry();
    const body = order(new Date());
    if (body) open.start(body, undefined);
  }

  return { channel, setChannel, draft, setDraft, blocked, open, awaitingRetry, locked, ready, submit };
}

function TakeawayNote() {
  return (
    <p className="flex items-start gap-3 text-muted-foreground lg:col-span-2">
      <Store aria-hidden="true" className="size-6 shrink-0" />O pedido abre já, sem mesa; o cliente levanta-o na loja.
    </p>
  );
}

type FooterProps = { pending: boolean; canCancel: boolean; canSubmit: boolean; label: string; onCancel: () => void };

function Footer({ pending, canCancel, canSubmit, label, onCancel }: FooterProps) {
  return (
    <DialogFooter>
      <Button type="button" variant="outline" size="touch" className="rounded-xl bg-card" onClick={onCancel} disabled={!canCancel}>
        Cancelar
      </Button>
      <Button type="submit" size="touch-primary" className="rounded-xl" disabled={!canSubmit || pending} aria-busy={pending}>
        {label}
      </Button>
    </DialogFooter>
  );
}
