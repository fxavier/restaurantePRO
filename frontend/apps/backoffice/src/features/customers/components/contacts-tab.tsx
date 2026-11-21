import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  Badge,
  Button,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from '@restaurantpro/ui';
import { MapPin, Phone, Plus, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { useAddAddress, useAddPhone, useDeletePhone } from '../hooks/use-customer-commands';
import { addressKindLabel } from '../labels';
import { addressSchema, phoneSchema } from '../schemas';
import type { AddressView, CustomerDetails, PhoneView } from '../types';
import { CheckboxField, CommandDialog, TextField } from './command-dialog';
import { CommandFeedback } from './command-feedback';

type ContactsTabProps = { customer: CustomerDetails; customerId: string; canManage: boolean };

/** "Moradas & Contactos": the phones (the primary one marked) and the billing and delivery addresses. */
export function ContactsTab({ customer, customerId, canManage }: ContactsTabProps) {
  return (
    <div className="grid gap-6 sm:grid-cols-2">
      <Phones phones={customer.phones ?? []} customerId={customerId} canManage={canManage} />
      <Addresses addresses={customer.addresses ?? []} customerId={customerId} canManage={canManage} />
    </div>
  );
}

function Phones({ phones, customerId, canManage }: { phones: PhoneView[]; customerId: string; canManage: boolean }) {
  const remove = useDeletePhone(customerId);
  const [toRemove, setToRemove] = useState<PhoneView | null>(null);
  const removeOne = (phone: { phoneId: string; label: string }) => remove.mutate(phone);
  return (
    <section aria-labelledby={`${customerId}-phones`} className="flex flex-col gap-3">
      <h3 id={`${customerId}-phones`} className="flex items-center gap-2 text-lg font-semibold">
        <Phone aria-hidden="true" className="size-5 text-muted-foreground" />
        Telefones
      </h3>
      {phones.length === 0 && <p className="text-muted-foreground">Sem telefones.</p>}
      <ul aria-label="Telefones" className="flex flex-col gap-2">
        {phones.map((phone) => (
          <li key={phone.id} className="flex flex-wrap items-center gap-2 rounded-lg border bg-muted px-3 py-2">
            <span className="font-mono">{phone.digits}</span>
            {phone.label && <span className="text-sm text-muted-foreground">{phone.label}</span>}
            {phone.primary && <Badge variant="success">Principal</Badge>}
            {canManage && (
              <Button
                variant="ghost"
                size="icon-sm"
                className="ml-auto"
                aria-label={`Remover ${phone.digits}`}
                disabled={remove.isPending}
                onClick={() => setToRemove(phone)}
              >
                <Trash2 aria-hidden="true" />
              </Button>
            )}
          </li>
        ))}
      </ul>
      <CommandFeedback
        error={remove.error}
        pending={remove.isPending}
        onRetry={() => remove.variables && removeOne(remove.variables)}
      />
      {canManage && <AddPhoneDialog customerId={customerId} />}
      <AlertDialog open={toRemove !== null} onOpenChange={(open) => !open && setToRemove(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Remover o telefone {toRemove?.digits}?</AlertDialogTitle>
            <AlertDialogDescription>O cliente deixa de ser encontrado por este número.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancelar</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => toRemove?.id && removeOne({ phoneId: toRemove.id, label: toRemove.digits ?? '' })}
            >
              Remover
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </section>
  );
}

function AddPhoneDialog({ customerId }: { customerId: string }) {
  const runner = useAddPhone(customerId);
  return (
    <CommandDialog
      trigger={
        <Button variant="outline" className="self-start">
          <Plus aria-hidden="true" />
          Adicionar telefone
        </Button>
      }
      title="Adicionar telefone"
      description="O cliente passa a ser encontrado também por este número."
      submitLabel="Adicionar"
      schema={phoneSchema}
      defaultValues={{ number: '', label: '', primary: false }}
      runner={runner}
      fields={(form) => (
        <>
          <TextField form={form} name="number" label="Número" type="tel" maxLength={40} />
          <TextField form={form} name="label" label="Etiqueta" maxLength={40} />
          <CheckboxField form={form} name="primary" label="Telefone principal" />
        </>
      )}
    />
  );
}

function Addresses({ addresses, customerId, canManage }: { addresses: AddressView[]; customerId: string; canManage: boolean }) {
  return (
    <section aria-labelledby={`${customerId}-addresses`} className="flex flex-col gap-3">
      <h3 id={`${customerId}-addresses`} className="flex items-center gap-2 text-lg font-semibold">
        <MapPin aria-hidden="true" className="size-5 text-muted-foreground" />
        Moradas
      </h3>
      {addresses.length === 0 && <p className="text-muted-foreground">Sem moradas.</p>}
      <ul aria-label="Moradas" className="flex flex-col gap-2">
        {addresses.map((address) => (
          <li key={address.id} className="flex flex-col gap-1 rounded-lg border bg-muted px-3 py-2">
            <Badge variant="secondary">{addressKindLabel(address.kind)}</Badge>
            <span>{address.line1}</span>
            {address.line2 && <span>{address.line2}</span>}
            <span>{[address.postalCode, address.city].filter(Boolean).join(' ')}</span>
            {address.country && <span>{address.country}</span>}
            {address.notes && <span className="text-sm text-muted-foreground">{address.notes}</span>}
          </li>
        ))}
      </ul>
      {canManage && <AddAddressDialog customerId={customerId} />}
    </section>
  );
}

function AddAddressDialog({ customerId }: { customerId: string }) {
  const runner = useAddAddress(customerId);
  return (
    <CommandDialog
      trigger={
        <Button variant="outline" className="self-start">
          <Plus aria-hidden="true" />
          Adicionar morada
        </Button>
      }
      title="Adicionar morada"
      description="Uma morada de faturação ou de entrega do cliente."
      submitLabel="Adicionar"
      schema={addressSchema}
      defaultValues={{ kind: 'BILLING', line1: '', line2: '', postalCode: '', city: '', country: '', notes: '' }}
      runner={runner}
      fields={(form) => (
        <>
          <FormField
            control={form.control}
            name="kind"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Tipo</FormLabel>
                <FormControl>
                  <select
                    {...field}
                    className="h-9 w-full max-w-48 rounded-md border border-input bg-muted px-3 text-sm focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                  >
                    <option value="BILLING">Faturação</option>
                    <option value="DELIVERY">Entrega</option>
                  </select>
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
          <TextField form={form} name="line1" label="Morada" maxLength={200} />
          <TextField form={form} name="line2" label="Complemento" maxLength={200} />
          <div className="grid gap-4 sm:grid-cols-2">
            <TextField form={form} name="postalCode" label="Código postal" maxLength={20} />
            <TextField form={form} name="city" label="Cidade" maxLength={100} />
          </div>
          <TextField form={form} name="country" label="País" maxLength={60} />
          <TextField form={form} name="notes" label="Indicações" />
        </>
      )}
    />
  );
}
