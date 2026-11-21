import type { components } from '@restaurantpro/api-client';
import { addressText, type AddressDraft } from '../delivery';
import { ChoiceGroup } from './choice-group';
import { TextField } from './text-field';

type AddressView = components['schemas']['com.xavier.backend.customers.model.dto.AddressView'];
type TypedAddress = Extract<AddressDraft, { kind: 'new' }>;

const TYPED: TypedAddress = { kind: 'new', line1: '', line2: '', postalCode: '', city: '' };
const OTHER = 'other';

type Props = {
  addresses: AddressView[];
  value: AddressDraft | undefined;
  onChange: (address: AddressDraft) => void;
  disabled: boolean;
};

/** Where the delivery goes: one of the customer's saved addresses, or another one typed for this order only. */
export function AddressPicker({ addresses, value, onChange, disabled }: Props) {
  const saved = savedChoices(addresses);
  const typed = typedAddress(value, saved.length > 0);
  return (
    <div className="flex flex-col gap-3">
      {saved.length > 0 && (
        <ChoiceGroup
          legend="Moradas do cliente"
          name="address"
          choices={[...saved, { value: OTHER, label: 'Outra morada' }]}
          value={chosen(value, typed)}
          onChange={(choice) => onChange(choice === OTHER ? TYPED : { kind: 'saved', addressId: choice })}
          disabled={disabled}
        />
      )}
      {typed && <AddressFields address={typed} onChange={onChange} readOnly={disabled} />}
    </div>
  );
}

function savedChoices(addresses: AddressView[]) {
  return addresses.flatMap(({ id, ...address }) =>
    id ? [{ value: id, label: addressText({ ...address, line1: address.line1 ?? '' }) }] : [],
  );
}

/** The address being typed: the one chosen as "Outra morada", or, without a saved address, the only way left. */
function typedAddress(value: AddressDraft | undefined, hasSaved: boolean): TypedAddress | undefined {
  if (value?.kind === 'new') return value;
  return hasSaved ? undefined : TYPED;
}

/** The radio that is on: a saved address, "Outra morada" while one is typed, or none yet. */
function chosen(value: AddressDraft | undefined, typed: TypedAddress | undefined): string | undefined {
  if (value?.kind === 'saved') return value.addressId;
  return typed && OTHER;
}

function AddressFields({ address, onChange, readOnly }: { address: TypedAddress; onChange: (a: TypedAddress) => void; readOnly: boolean }) {
  const field = (part: keyof Omit<TypedAddress, 'kind'>) => ({
    value: address[part],
    onChange: (text: string) => onChange({ ...address, [part]: text }),
    readOnly,
  });
  return (
    <div className="grid grid-cols-2 gap-2">
      <TextField label="Morada" className="col-span-2" maxLength={200} {...field('line1')} />
      <TextField label="Complemento" className="col-span-2" maxLength={200} {...field('line2')} />
      <TextField label="Código postal" maxLength={20} {...field('postalCode')} />
      <TextField label="Cidade" maxLength={100} {...field('city')} />
    </div>
  );
}
