import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  Button,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
  StatusPill,
} from '@restaurantpro/ui';
import { MapPin, Pencil, Phone, Plus, Star, Trash2 } from 'lucide-react';
import { useState } from 'react';
import type { FieldValues, UseFormReturn } from 'react-hook-form';
import type { z } from 'zod';
import { useAddAddress, useAddPhone, useDeletePhone, useUpdateAddress } from '../hooks/use-customer-commands';
import { addressKindLabel } from '../labels';
import { addressEditSchema, addressSchema, phoneSchema } from '../schemas';
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
          <PhoneItem
            key={phone.id}
            phone={phone}
            onRemove={canManage ? () => setToRemove(phone) : undefined}
            disabled={remove.isPending}
          />
        ))}
      </ul>
      <CommandFeedback
        error={remove.error}
        pending={remove.isPending}
        onRetry={() => remove.variables && removeOne(remove.variables)}
      />
      {canManage && <AddPhoneDialog customerId={customerId} />}
      <RemovePhoneConfirm
        phone={toRemove}
        onClose={() => setToRemove(null)}
        onConfirm={(phone) => phone.id && removeOne({ phoneId: phone.id, label: phone.digits ?? '' })}
      />
    </section>
  );
}

/** One phone; the remove button only when the user may manage customers (`onRemove` given). */
function PhoneItem({ phone, onRemove, disabled }: { phone: PhoneView; onRemove?: () => void; disabled: boolean }) {
  return (
    <li className="flex min-h-12 flex-wrap items-center gap-2 rounded-xl border bg-muted px-3 py-2">
      <span className="font-mono">{phone.digits}</span>
      {phone.label && <span className="text-sm text-muted-foreground">{phone.label}</span>}
      {phone.primary && (
        <StatusPill tone="success" icon={Star}>
          Principal
        </StatusPill>
      )}
      {onRemove && (
        <Button
          variant="ghost"
          size="icon-sm"
          className="ml-auto"
          aria-label={`Remover ${phone.digits}`}
          disabled={disabled}
          onClick={onRemove}
        >
          <Trash2 aria-hidden="true" />
        </Button>
      )}
    </li>
  );
}

type RemovePhoneConfirmProps = {
  phone: PhoneView | null;
  onClose: () => void;
  onConfirm: (phone: PhoneView) => void;
};

function RemovePhoneConfirm({ phone, onClose, onConfirm }: RemovePhoneConfirmProps) {
  return (
    <AlertDialog open={phone !== null} onOpenChange={(open) => !open && onClose()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Remover o telefone {phone?.digits}?</AlertDialogTitle>
          <AlertDialogDescription>O cliente deixa de ser encontrado por este número.</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancelar</AlertDialogCancel>
          <AlertDialogAction onClick={() => phone && onConfirm(phone)}>Remover</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
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
          <li key={address.id} className="flex flex-col gap-1 rounded-xl border bg-muted px-3 py-2">
            <div className="flex items-center justify-between gap-2">
              <StatusPill tone="neutral">{addressKindLabel(address.kind)}</StatusPill>
              {canManage && address.id && (
                <EditAddressDialog address={address} addressId={address.id} customerId={customerId} />
              )}
            </div>
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
      fields={(form) => <AddressFields form={form} />}
    />
  );
}

/** The edit sends the version the dialog opened with: if someone saved meanwhile, the server answers 409. */
function EditAddressDialog({ address, addressId, customerId }: { address: AddressView; addressId: string; customerId: string }) {
  const runner = useUpdateAddress(customerId, addressId);
  return (
    <CommandDialog
      trigger={
        <Button variant="ghost" size="icon-sm" className="self-end" aria-label={`Editar morada ${address.line1 ?? ''}`}>
          <Pencil aria-hidden="true" />
        </Button>
      }
      title="Editar morada"
      description="A morada passa a valer assim em todas as lojas."
      submitLabel="Guardar"
      schema={addressEditSchema}
      defaultValues={editDefaults(address)}
      runner={runner}
      fields={(form) => <AddressFields form={form} />}
    />
  );
}

type AddressValues = z.input<typeof addressSchema>;

/** The address as read, with its version, as the edit form starts. */
function editDefaults(address: AddressView): z.input<typeof addressEditSchema> {
  return {
    kind: address.kind === 'DELIVERY' ? 'DELIVERY' : 'BILLING',
    line1: address.line1 ?? '',
    line2: address.line2 ?? '',
    postalCode: address.postalCode ?? '',
    city: address.city ?? '',
    country: address.country ?? '',
    notes: address.notes ?? '',
    version: address.version ?? 0,
  };
}

/** The fields of an address form (add or edit). */
function AddressFields<Values extends FieldValues>({ form: anyForm }: { form: UseFormReturn<Values> }) {
  // Both forms hold these fields; the edit form only adds its version.
  const form = anyForm as unknown as UseFormReturn<AddressValues>;
  return (
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
                className="h-9 w-full max-w-48 rounded-xl border border-input bg-card px-3 text-sm focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
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
  );
}
