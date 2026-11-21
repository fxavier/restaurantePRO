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
  const saved = addresses.flatMap(({ id, ...address }) =>
    id ? [{ value: id, label: addressText({ ...address, line1: address.line1 ?? '' }) }] : [],
  );
  // Without a saved address, the only way is to type one.
  const typed = value?.kind === 'new' ? value : saved.length === 0 ? TYPED : undefined;
  return (
    <div className="flex flex-col gap-3">
      {saved.length > 0 && (
        <ChoiceGroup
          legend="Moradas do cliente"
          name="address"
          choices={[...saved, { value: OTHER, label: 'Outra morada' }]}
          value={value?.kind === 'saved' ? value.addressId : typed && OTHER}
          onChange={(choice) => onChange(choice === OTHER ? TYPED : { kind: 'saved', addressId: choice })}
          disabled={disabled}
        />
      )}
      {typed && <AddressFields address={typed} onChange={onChange} readOnly={disabled} />}
    </div>
  );
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
