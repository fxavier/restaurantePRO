import { ApiError, type components } from '@restaurantpro/api-client';
import { LOCALE } from '@restaurantpro/ui';
import type { Employee } from '../../lib/auth/pos-session';
import { isVersionConflict } from '../../lib/error-messages';

type Schemas = components['schemas'];
type DeliveryView = Schemas['com.xavier.backend.orders.delivery.model.dto.DeliveryView'];
export type AddressRequest = Schemas['com.xavier.backend.orders.delivery.model.dto.AddressRequest'];
export type DeliveryStatus = NonNullable<DeliveryView['status']>;

/** A delivery as the board shows it (§8.3), parsed once where the answer comes in. */
export type Delivery = {
  orderId: string;
  orderNumber: number;
  status: DeliveryStatus;
  /** Sent back with every transition (D133): a stale one is refused with 409. */
  version: number;
  zoneCode: string;
  phone?: string;
  address?: AddressRequest;
  promisedAt?: string;
  /** Why it last came back, when it did. */
  returnReason?: string;
};

/** The board's columns: what the POS works on (`GET /deliveries` without a status answers these three). */
export const BOARD = [
  { status: 'PENDING', title: 'Por expedir' },
  { status: 'OUT_FOR_DELIVERY', title: 'Em entrega' },
  { status: 'RETURNED', title: 'Devolvidas' },
] as const satisfies readonly { status: DeliveryStatus; title: string }[];

/** A view without its identity, number, status or version is not a delivery the board can act on: it is dropped. */
export function toDeliveries(views: DeliveryView[] | undefined): Delivery[] {
  return (views ?? []).flatMap((view) => {
    const { orderId, orderNumber, status, version } = view;
    if (!orderId || orderNumber === undefined || !status || version === undefined) return [];
    return [
      {
        orderId,
        orderNumber,
        status,
        version,
        zoneCode: view.zoneCode ?? '',
        phone: view.phone,
        address: view.address,
        promisedAt: view.promisedAt,
        returnReason: view.history?.findLast((event) => event.toStatus === 'RETURNED')?.reason,
      },
    ];
  });
}

/**
 * Where a command's intent stands (FC §8): an unknown outcome keeps its intent, and only "Tentar novamente", with the
 * same key, may follow; meanwhile, and while it is in flight, what it sends cannot change.
 */
export function intentState({ pending, intent }: { pending: boolean; intent: unknown }) {
  const awaitingRetry = !pending && intent !== undefined;
  return { awaitingRetry, locked: pending || awaitingRetry };
}

/** A command on a delivery (D127), each at the version the board showed. */
export type Transition =
  | { kind: 'dispatch'; courierEmployeeId: string; version: number }
  | { kind: 'delivered'; version: number }
  | { kind: 'returned'; reason: string; version: number };

/** Refused because the delivery moved meanwhile: 409 `DELIVERY_STATE`, or a stale version. */
export function isChangedElsewhere(error: unknown): boolean {
  return isVersionConflict(error) || (error instanceof ApiError && error.code === 'DELIVERY_STATE');
}

/** Whether the session may run deliveries in its store; for showing commands only, the backend enforces. */
export function mayRunDeliveries(employee: Pick<Employee, 'permissions' | 'storeId'> | undefined): boolean {
  return !!employee?.permissions.includes(`PERM_DELIVERY_OPERATE@${employee.storeId}`);
}

/** Where it goes: one of the customer's saved addresses, or one typed for this order (its fields as typed). */
export type AddressDraft =
  | { kind: 'saved'; addressId: string }
  | { kind: 'new'; line1: string; line2: string; postalCode: string; city: string };

/** The new delivery as the modal holds it, before it is sent. */
export type DeliveryDraft = {
  customerId?: string;
  address?: AddressDraft;
  zoneId?: string;
  /** Minutes from now the customer is promised the delivery; `null` is "as soon as possible", no promise. */
  promiseMinutes: number | null;
  notes: string;
};

type OpenOrderRequest = Schemas['com.xavier.backend.orders.model.dto.OpenOrderRequest'];
type DeliveryRequest = NonNullable<OpenOrderRequest['delivery']>;

/**
 * The `POST /orders` body of a complete draft (D127: customer, address and zone), or nothing while one is missing.
 * Text is sent trimmed and only when filled in; a promise is an instant, `now` plus its minutes.
 */
export function deliveryOrder(draft: DeliveryDraft, now: Date): OpenOrderRequest | undefined {
  const { customerId, address, zoneId } = draft;
  const where = address && whereOf(address);
  if (!customerId || !zoneId || !where) return undefined;
  const delivery: DeliveryRequest = { customerId, ...where, zoneId };
  if (draft.promiseMinutes !== null) {
    delivery.promisedAt = new Date(now.getTime() + draft.promiseMinutes * 60_000).toISOString();
  }
  const notes = draft.notes.trim();
  if (notes) delivery.notes = notes;
  return { mode: 'DELIVERY', delivery };
}

function whereOf(address: AddressDraft): Pick<DeliveryRequest, 'addressId' | 'address'> | undefined {
  if (address.kind === 'saved') return { addressId: address.addressId };
  const line1 = address.line1.trim();
  if (!line1) return undefined;
  const typed: AddressRequest = { line1 };
  for (const part of ['line2', 'postalCode', 'city'] as const) {
    const value = address[part].trim();
    if (value) typed[part] = value;
  }
  return { address: typed };
}

type CustomerCreateRequest = Schemas['com.xavier.backend.customers.model.dto.CustomerCreateRequest'];

/** A new customer as typed (`POST /customers`): a name is required, a phone only when given; both trimmed. */
export function newCustomer(name: string, phone: string): CustomerCreateRequest | undefined {
  const named = name.trim();
  if (!named) return undefined;
  const digits = phone.trim();
  return digits ? { name: named, phone: digits } : { name: named };
}

/** "Av. Julius Nyerere 1200, 3.º andar, 1100 Maputo": the parts the address has, in reading order. */
export function addressText(address: AddressRequest | undefined): string {
  if (!address) return '';
  const town = [address.postalCode, address.city].filter(Boolean).join(' ');
  return [address.line1, address.line2, town].filter(Boolean).join(', ');
}

/** "20:15": an instant as the store's clock shows it, never the terminal's (FC §12). */
export function storeClock(instant: string, timeZone: string): string {
  return new Intl.DateTimeFormat(LOCALE, { timeStyle: 'short', timeZone }).format(new Date(instant));
}
