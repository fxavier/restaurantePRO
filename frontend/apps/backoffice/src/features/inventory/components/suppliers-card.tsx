import { zodResolver } from '@hookform/resolvers/zod';
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { Plus } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useFormCommand } from '../hooks/use-form-command';
import { useCreateSupplier } from '../hooks/use-inventory-commands';
import { LIST_PAGE_SIZE, useSuppliers } from '../hooks/use-inventory-reads';
import { usePermission } from '../hooks/use-permission';
import { supplierSchema, type SupplierInput, type SupplierValues } from '../schemas';
import type { SupplierRequest } from '../types';
import { CommandForm, TextField } from './command-form';
import { FormDialog } from './form-dialog';
import { Pages, QueryState } from './query-state';

/** "Fornecedores" (§5 purchasing): the company's suppliers, and a new one. */
export function SuppliersCard({ storeId }: { storeId: string }) {
  const [page, setPage] = useState(0);
  const suppliers = useSuppliers(page);
  const canManage = usePermission('PURCHASE_MANAGE', storeId);
  const rows = suppliers.data?.rows;

  return (
    <Card className="gap-4">
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle className="text-lg">Fornecedores</CardTitle>
        {canManage && (
          <FormDialog
            trigger={
              <>
                <Plus aria-hidden="true" />
                Novo fornecedor
              </>
            }
            triggerVariant="outline"
            title="Novo fornecedor"
            description="Um fornecedor da empresa, disponível para as encomendas de todas as lojas."
          >
            {(close) => <SupplierForm onDone={close} />}
          </FormDialog>
        )}
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <QueryState query={suppliers} pending={suppliers.isPending} empty={!rows?.length} emptyMessage="Ainda não há fornecedores.">
          <Table>
            <TableCaption className="sr-only">Fornecedores</TableCaption>
            <TableHeader>
              <TableRow>
                <TableHead>Código</TableHead>
                <TableHead>Nome</TableHead>
                <TableHead>N.º contribuinte</TableHead>
                <TableHead>Contacto</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows?.map((supplier) => (
                <TableRow key={supplier.id}>
                  <TableCell className="font-mono">{supplier.code}</TableCell>
                  <TableCell className="font-medium">{supplier.name}</TableCell>
                  <TableCell className="tabular-nums">{supplier.taxId ?? '—'}</TableCell>
                  <TableCell className="text-muted-foreground">{supplier.phone ?? supplier.email ?? '—'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <Pages page={page} total={suppliers.data?.totalElements ?? 0} size={LIST_PAGE_SIZE} onPage={setPage} />
        </QueryState>
      </CardContent>
    </Card>
  );
}

function SupplierForm({ onDone }: { onDone: () => void }) {
  const form = useForm<SupplierInput, unknown, SupplierValues>({
    resolver: zodResolver(supplierSchema),
    defaultValues: { code: '', name: '', taxId: '', phone: '', email: '' },
  });
  const mutation = useCreateSupplier();
  const command = useFormCommand({
    form,
    fields: ['code', 'name', 'taxId', 'phone', 'email'],
    mutation,
    toVariables: (intent: { body: SupplierRequest; idempotencyKey: string }) => intent,
    onDone,
  });
  /** Empty optional fields are left out rather than sent blank. */
  const submit = (values: SupplierValues) =>
    command.send(Object.fromEntries(Object.entries(values).filter(([, value]) => value !== '')) as SupplierRequest);

  return (
    <CommandForm form={form} onSubmit={submit} command={command} pending={mutation.isPending} submitLabel="Criar fornecedor" onCancel={onDone}>
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField control={form.control} name="code" label="Código" maxLength={40} />
        <TextField control={form.control} name="name" label="Nome" maxLength={160} />
        <TextField control={form.control} name="taxId" label="N.º de contribuinte" maxLength={40} />
        <TextField control={form.control} name="phone" label="Telefone" maxLength={40} type="tel" />
        <TextField control={form.control} name="email" label="Email" maxLength={200} type="email" />
      </div>
    </CommandForm>
  );
}
