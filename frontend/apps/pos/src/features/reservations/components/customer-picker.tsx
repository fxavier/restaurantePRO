import { ApiError } from '@restaurantpro/api-client';
import { Button, Input } from '@restaurantpro/ui';
import { BadgeCheck, Search, UserPlus, X } from 'lucide-react';
import { type KeyboardEvent, useState } from 'react';
import { errorMessage, isOffline } from '../../../lib/error-messages';
import { useCommandsBlocked } from '../../../lib/live/use-connection';
import { useCan } from '../hooks/use-can';
import { type CustomerMatch, useCreateCustomer, useCustomer, useCustomerSearch } from '../hooks/use-customers';
import { refusal } from '../messages';

type Props = {
  customerId: string | null;
  /** The contact typed so far: a new customer record is created from it. */
  contact: { name: string; phone: string };
  onLink: (customer: CustomerMatch) => void;
  onUnlink: () => void;
  locked: boolean;
};

/**
 * Stitch's customer line: find a customer by name or phone and link the reservation to them (their contact fills
 * the form), or create the record from the contact typed. Each part only for who holds its permission.
 */
export function CustomerPicker({ customerId, contact, onLink, onUnlink, locked }: Props) {
  const canView = useCan('CUSTOMER_VIEW');
  const canCreate = useCan('CUSTOMER_MANAGE');
  if (customerId) {
    return <LinkedCustomer customerId={customerId} canView={canView} onUnlink={onUnlink} locked={locked} />;
  }
  return (
    <div className="flex flex-col gap-3">
      {canView && <CustomerSearch onLink={onLink} locked={locked} />}
      {canCreate && <CreateCustomer contact={contact} onLink={onLink} locked={locked} />}
    </div>
  );
}

type LinkedProps = { customerId: string; canView: boolean; onUnlink: () => void; locked: boolean };

/** The record linked; its name read only by who may view customers, and the link shown whatever that read says. */
function LinkedCustomer({ customerId, canView, onUnlink, locked }: LinkedProps) {
  const customer = useCustomer(customerId, { enabled: canView });
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-xl border-2 border-primary bg-success-tint p-3">
      <BadgeCheck aria-hidden="true" className="size-5 text-success-text" />
      {canView ? (
        <p>
          <span className="text-muted-foreground">Cliente associado:</span>{' '}
          {customer.isError ? (
            <span className="text-muted-foreground">Ficha de cliente indisponível</span>
          ) : (
            <span className="font-semibold">{customer.data?.name ?? '…'}</span>
          )}
        </p>
      ) : (
        <p className="text-muted-foreground">Cliente associado</p>
      )}
      <Button type="button" variant="outline" size="touch" className="ml-auto" disabled={locked} onClick={onUnlink}>
        <X aria-hidden="true" />
        Desassociar cliente
      </Button>
    </div>
  );
}

function CustomerSearch({ onLink, locked }: { onLink: (customer: CustomerMatch) => void; locked: boolean }) {
  const [text, setText] = useState('');
  const [q, setQ] = useState('');
  const found = useCustomerSearch(q);
  const submit = () => setQ(text.trim());
  const onKeyDown = (event: KeyboardEvent) => {
    // Enter searches; it must not save the reservation around it.
    if (event.key !== 'Enter') return;
    event.preventDefault();
    submit();
  };

  return (
    <div className="flex flex-col gap-2">
      <div className="flex gap-2">
        <div className="relative flex-1">
          <Search aria-hidden="true" className="absolute top-1/2 left-3 size-5 -translate-y-1/2 text-muted-foreground" />
          <Input
            type="search"
            aria-label="Procurar cliente"
            placeholder="Procurar cliente por nome ou telefone"
            value={text}
            readOnly={locked}
            onChange={(event) => setText(event.target.value)}
            onKeyDown={onKeyDown}
            className="h-12 pl-10 text-base md:text-base"
          />
        </div>
        <Button
          type="button"
          variant="outline"
          size="touch"
          disabled={locked || text.trim() === ''}
          onClick={submit}
        >
          Procurar
        </Button>
      </div>
      {found.isError ? (
        <p role="alert" className="font-medium text-error">
          {searchError(found.error)}
        </p>
      ) : found.data?.length === 0 ? (
        <p className="text-muted-foreground">Nenhum cliente encontrado.</p>
      ) : found.data ? (
        <ul aria-label="Clientes encontrados" className="flex flex-col gap-2">
          {found.data.map((customer) => (
            <li key={customer.id} className="flex flex-wrap items-center gap-3 rounded-xl border bg-card p-3">
              <span className="font-semibold">{customer.name}</span>
              <span className="text-sm text-muted-foreground tabular-nums">{customer.phones.join(', ')}</span>
              <Button
                type="button"
                size="touch"
                className="ml-auto"
                aria-label={`Associar ${customer.name}`}
                disabled={locked}
                onClick={() => onLink(customer)}
              >
                Associar
              </Button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

function CreateCustomer({ contact, onLink, locked }: Pick<Props, 'contact' | 'onLink' | 'locked'>) {
  const blocked = useCommandsBlocked();
  const create = useCreateCustomer({
    onCreated: ({ id, name }) => id && onLink({ id, name: name ?? contact.name, phones: [] }),
  });
  const awaitingRetry = !create.pending && create.intent !== undefined;
  const name = contact.name.trim();
  const phone = contact.phone.trim();

  function run() {
    if (awaitingRetry) create.retry();
    else create.start({ name, ...(phone && { phone }) }, undefined);
  }

  return (
    <div className="flex flex-col gap-2">
      <Button
        type="button"
        variant="outline"
        size="touch"
        className="self-start"
        aria-busy={create.pending}
        disabled={locked || blocked || create.pending || name === ''}
        onClick={run}
      >
        <UserPlus aria-hidden="true" />
        {awaitingRetry ? 'Tentar criar de novo' : 'Criar ficha de cliente'}
      </Button>
      {create.error && (
        <p role="alert" className="font-medium text-error">
          {refusal(create.error, 'Sem ligação — não se sabe se a ficha de cliente foi criada.')}
        </p>
      )}
    </div>
  );
}

function searchError(error: Error): string {
  if (error instanceof ApiError && isOffline(error)) return 'Sem ligação — não foi possível procurar clientes.';
  return `Não foi possível procurar clientes. ${error instanceof ApiError ? errorMessage(error) : ''}`.trim();
}
