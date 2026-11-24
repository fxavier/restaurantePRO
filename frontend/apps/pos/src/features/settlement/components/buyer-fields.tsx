import { cn, Input, Label } from '@restaurantpro/ui';
import type { ReactNode } from 'react';
import type { BuyerChoice } from '../settlement';

type Props = { value: BuyerChoice; onChange: (value: BuyerChoice) => void };

/** Stitch's "Identificação fiscal": who the next document is for, the order's or a part's. */
export function BuyerFields({ value, onChange }: Props) {
  return (
    <fieldset className="flex flex-col gap-3">
      <legend className="sr-only">Identificação fiscal</legend>
      <p aria-hidden="true" className="text-base font-semibold">
        Identificação fiscal
      </p>
      <div className="grid grid-cols-2 gap-2">
        <Choice checked={!value.withTaxId} onChange={() => onChange({ ...value, withTaxId: false })}>
          Consumidor final
        </Choice>
        <Choice checked={value.withTaxId} onChange={() => onChange({ ...value, withTaxId: true })}>
          Com NIF
        </Choice>
      </div>
      {value.withTaxId && (
        <div className="flex flex-col gap-3">
          <Field id="buyer-tax-id" label="NIF" value={value.taxId} onChange={(taxId) => onChange({ ...value, taxId })} />
          <Field id="buyer-name" label="Nome" value={value.name} onChange={(name) => onChange({ ...value, name })} />
          <Field id="buyer-address" label="Morada" value={value.address} onChange={(address) => onChange({ ...value, address })} />
        </div>
      )}
    </fieldset>
  );
}

function Choice({ checked, onChange, children }: { checked: boolean; onChange: () => void; children: ReactNode }) {
  return (
    <label
      className={cn(
        'flex min-h-12 cursor-pointer items-center justify-center rounded-xl border-2 px-3 text-center font-semibold has-focus-visible:ring-2 has-focus-visible:ring-ring',
        checked ? 'border-primary bg-primary text-primary-foreground' : 'border-border bg-card hover:bg-muted',
      )}
    >
      <input type="radio" name="buyer-kind" checked={checked} onChange={onChange} className="sr-only" />
      {children}
    </label>
  );
}

function Field({ id, label, value, onChange }: { id: string; label: string; value: string; onChange: (value: string) => void }) {
  return (
    <div className="flex flex-col gap-1">
      <Label htmlFor={id}>{label}</Label>
      <Input id={id} autoComplete="off" value={value} onChange={(event) => onChange(event.target.value)} className="h-12" />
    </div>
  );
}
