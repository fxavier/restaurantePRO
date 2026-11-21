import { Button } from '@restaurantpro/ui';
import { Pencil } from 'lucide-react';
import { useUpdateCustomer } from '../hooks/use-customer-commands';
import { customerSchema } from '../schemas';
import type { CustomerDetails } from '../types';
import { CheckboxField, CommandDialog, TextField } from './command-dialog';

/** "Dados": who the customer is, and the edit for a user who may manage customers. */
export function CustomerDataTab({ customer, canManage }: { customer: CustomerDetails; canManage: boolean }) {
  return (
    <div className="flex flex-col gap-4">
      <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2">
        <Field label="Nome">{customer.name}</Field>
        <Field label="NIF">{customer.taxId ? <span className="font-mono">{customer.taxId}</span> : '—'}</Field>
        <Field label="Email">{customer.email ?? '—'}</Field>
        <Field label="Estado">{customer.active === false ? 'Inativo' : 'Ativo'}</Field>
        <Field label="Notas">{customer.notes ?? '—'}</Field>
      </dl>
      {canManage && customer.id && <EditCustomerDialog customer={customer} customerId={customer.id} />}
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">{label}</dt>
      <dd className="whitespace-pre-line">{children}</dd>
    </div>
  );
}

/** The edit sends the version the dialog opened with: if someone saved meanwhile, the server answers 409. */
function EditCustomerDialog({ customer, customerId }: { customer: CustomerDetails; customerId: string }) {
  const runner = useUpdateCustomer(customerId);
  return (
    <CommandDialog
      trigger={
        <Button variant="outline" className="self-start">
          <Pencil aria-hidden="true" />
          Editar dados
        </Button>
      }
      title="Editar dados do cliente"
      description="As alterações valem em todas as lojas."
      submitLabel="Guardar"
      schema={customerSchema}
      defaultValues={{
        name: customer.name ?? '',
        taxId: customer.taxId ?? '',
        email: customer.email ?? '',
        notes: customer.notes ?? '',
        active: customer.active !== false,
        version: customer.version ?? 0,
      }}
      runner={runner}
      fields={(form) => (
        <>
          <TextField form={form} name="name" label="Nome" maxLength={160} />
          <TextField form={form} name="taxId" label="NIF" maxLength={30} className="max-w-48 font-mono" />
          <TextField form={form} name="email" label="Email" type="email" maxLength={254} />
          <TextField form={form} name="notes" label="Notas" />
          <CheckboxField form={form} name="active" label="Cliente ativo" />
        </>
      )}
    />
  );
}
