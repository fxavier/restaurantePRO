import { Button } from '@restaurantpro/ui';
import { UserPlus } from 'lucide-react';
import { useCreateCustomer } from '../hooks/use-customer-commands';
import { newCustomerSchema } from '../schemas';
import { CommandDialog, TextField } from './command-dialog';

/** Stitch's "Novo Cliente": a customer with a name and, optionally, a tax id, email, phone and notes; then opened. */
export function NewCustomerDialog({ onCreated }: { onCreated: (customerId: string) => void }) {
  const runner = useCreateCustomer();
  return (
    <CommandDialog
      trigger={
        <Button>
          <UserPlus aria-hidden="true" />
          Novo cliente
        </Button>
      }
      title="Novo cliente"
      description="O cliente fica disponível em todas as lojas da empresa."
      submitLabel="Criar cliente"
      schema={newCustomerSchema}
      defaultValues={{ name: '', taxId: '', email: '', phone: '', notes: '' }}
      runner={runner}
      onDone={(customer) => customer.id && onCreated(customer.id)}
      fields={(form) => (
        <>
          <TextField form={form} name="name" label="Nome" maxLength={160} />
          <TextField form={form} name="taxId" label="NIF" maxLength={30} className="max-w-48 font-mono" />
          <TextField form={form} name="email" label="Email" type="email" maxLength={254} />
          <TextField form={form} name="phone" label="Telefone" type="tel" maxLength={40} />
          <TextField form={form} name="notes" label="Notas" />
        </>
      )}
    />
  );
}
