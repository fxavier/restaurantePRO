import { Button, Skeleton } from '@restaurantpro/ui';
import { Search, UserPlus, UserRound } from 'lucide-react';
import { type KeyboardEvent, useEffect, useState } from 'react';
import { useCommandsBlocked } from '../../../lib/live/use-connection';
import { intentState, newCustomer } from '../delivery';
import { MIN_SEARCH, useCreateCustomer, useCustomer, useCustomerSearch } from '../hooks/use-customers';
import { CUSTOMER_UNSETTLED, loadFailure } from '../messages';
import { CommandMessage } from './command-message';
import { TextField } from './text-field';

type Props = {
  customerId: string | undefined;
  onChange: (customerId: string | undefined) => void;
  disabled: boolean;
  /** A "Criar cliente" without an answer, or settled: while it waits, nothing may unmount it and lose its key. */
  onUnsettledChange: (unsettled: boolean) => void;
};

/** Who the delivery is for (D127): a customer found by name or phone, or one created here with a name and phone. */
export function CustomerPicker({ customerId, onChange, disabled, onUnsettledChange }: Props) {
  const [creating, setCreating] = useState(false);
  if (customerId) return <ChosenCustomer customerId={customerId} onChange={() => onChange(undefined)} disabled={disabled} />;
  if (creating) return <NewCustomer onCreated={onChange} onCancel={() => setCreating(false)} onUnsettledChange={onUnsettledChange} />;
  return <CustomerSearch onChoose={onChange} onCreate={() => setCreating(true)} disabled={disabled} />;
}

function ChosenCustomer({ customerId, onChange, disabled }: { customerId: string; onChange: () => void; disabled: boolean }) {
  const customer = useCustomer(customerId);
  return (
    <div className="flex min-h-14 items-center gap-3 rounded-lg border border-primary bg-primary/15 px-3 py-2">
      <UserRound aria-hidden="true" className="size-6 shrink-0 text-primary-hover" />
      <div className="flex min-w-0 flex-1 flex-col">
        {customer.data ? (
          <>
            <span className="font-semibold">{customer.data.name}</span>
            <span className="text-sm text-muted-foreground tabular-nums">
              {customer.data.phones?.map((phone) => phone.digits).join(' · ')}
            </span>
          </>
        ) : customer.isError ? (
          <span role="alert" className="text-error">
            {loadFailure(customer.error, 'o cliente')}
          </span>
        ) : (
          <Skeleton className="h-6 w-40" />
        )}
      </div>
      <Button type="button" variant="outline" size="touch" onClick={onChange} disabled={disabled}>
        Trocar
      </Button>
    </div>
  );
}

function CustomerSearch({ onChoose, onCreate, disabled }: { onChoose: (id: string) => void; onCreate: () => void; disabled: boolean }) {
  const [text, setText] = useState('');
  const [submitted, setSubmitted] = useState<string>();
  const found = useCustomerSearch(submitted);
  const ready = text.trim().length >= MIN_SEARCH;

  function search() {
    if (ready) setSubmitted(text.trim());
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    // The dialog's own form must not be sent: Enter here searches.
    if (event.key !== 'Enter') return;
    event.preventDefault();
    search();
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-end gap-2">
        <TextField
          label="Nome ou telefone do cliente"
          className="flex-1"
          value={text}
          onChange={setText}
          onKeyDown={onKeyDown}
          disabled={disabled}
        />
        <Button type="button" variant="outline" size="touch" onClick={search} disabled={disabled || !ready}>
          <Search aria-hidden="true" />
          Procurar
        </Button>
      </div>
      {found.data &&
        (found.data.length === 0 ? (
          <p className="text-muted-foreground">Nenhum cliente encontrado.</p>
        ) : (
          <ul aria-label="Clientes encontrados" className="flex flex-col gap-2">
            {found.data.map(({ id, name, phones }) =>
              id ? (
                <li key={id}>
                  <Button
                    type="button"
                    variant="outline"
                    size="touch"
                    className="h-auto min-h-14 w-full justify-start py-2 text-left"
                    onClick={() => onChoose(id)}
                    disabled={disabled}
                  >
                    <UserRound aria-hidden="true" />
                    <span className="flex flex-col">
                      <span>{name}</span>
                      <span className="text-sm font-normal text-muted-foreground tabular-nums">{phones?.join(' · ')}</span>
                    </span>
                  </Button>
                </li>
              ) : null,
            )}
          </ul>
        ))}
      {found.isError && (
        <p role="alert" className="text-error">
          {loadFailure(found.error, 'os clientes')}
        </p>
      )}
      <Button type="button" variant="outline" size="touch" className="self-start" onClick={onCreate} disabled={disabled}>
        <UserPlus aria-hidden="true" />
        Novo cliente
      </Button>
    </div>
  );
}

type NewCustomerProps = {
  onCreated: (id: string) => void;
  onCancel: () => void;
  onUnsettledChange: (unsettled: boolean) => void;
};

/**
 * A customer created here. A "Criar cliente" without an answer may have created them: it holds its intent, so the
 * only way on is "Tentar novamente" with the same key (FC §8); going back would make the next tap a second customer.
 */
function NewCustomer({ onCreated, onCancel, onUnsettledChange }: NewCustomerProps) {
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const blocked = useCommandsBlocked();
  const create = useCreateCustomer({ onCreated });
  const { awaitingRetry, locked } = intentState(create);
  const request = newCustomer(name, phone);

  useEffect(() => {
    onUnsettledChange(awaitingRetry);
    return () => onUnsettledChange(false);
  }, [awaitingRetry, onUnsettledChange]);

  function submit() {
    if (blocked || !request) return;
    if (awaitingRetry) create.retry();
    else create.start(request, undefined);
  }

  return (
    <div className="flex flex-col gap-3">
      <TextField label="Nome" value={name} onChange={setName} readOnly={locked} maxLength={160} />
      <TextField label="Telefone" value={phone} onChange={setPhone} readOnly={locked} inputMode="tel" />
      {/* "Sem ligação" is said once, by the dialog. */}
      <CommandMessage blocked={false} error={create.error} what="a criação do cliente" />
      {awaitingRetry && <p className="text-muted-foreground">{CUSTOMER_UNSETTLED}</p>}
      <div className="flex gap-2">
        <Button type="button" variant="outline" size="touch" onClick={onCancel} disabled={locked}>
          Voltar à pesquisa
        </Button>
        <Button type="button" size="touch" onClick={submit} disabled={blocked || !request || create.pending} aria-busy={create.pending}>
          {awaitingRetry ? 'Tentar novamente' : 'Criar cliente'}
        </Button>
      </div>
    </div>
  );
}
