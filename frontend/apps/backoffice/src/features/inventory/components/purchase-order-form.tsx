import { zodResolver } from '@hookform/resolvers/zod';
import { Button } from '@restaurantpro/ui';
import { Plus, Trash2 } from 'lucide-react';
import { useFieldArray, useForm } from 'react-hook-form';
import { useFormCommand } from '../hooks/use-form-command';
import { useCreatePurchaseOrder } from '../hooks/use-inventory-commands';
import { useStockItems, useSuppliers, useWarehouses } from '../hooks/use-inventory-reads';
import { purchaseOrderSchema, type PurchaseOrderInput, type PurchaseOrderValues } from '../schemas';
import type { PurchaseOrderRequest } from '../types';
import { CommandForm, SelectField, TextField } from './command-form';

const EMPTY_LINE = { stockItemId: '', quantity: '', unitCost: '' };

/** "Nova encomenda" (§5 purchasing): number, supplier, warehouse, expected date and one or more lines. */
export function PurchaseOrderForm({ storeId, onDone }: { storeId: string; onDone: () => void }) {
  const suppliers = useSuppliers();
  const warehouses = useWarehouses(storeId);
  const items = useStockItems();
  const form = useForm<PurchaseOrderInput, unknown, PurchaseOrderValues>({
    resolver: zodResolver(purchaseOrderSchema),
    defaultValues: { number: '', supplierId: '', warehouseId: '', expectedAt: '', lines: [EMPTY_LINE] },
  });
  const lines = useFieldArray({ control: form.control, name: 'lines' });
  const mutation = useCreatePurchaseOrder();
  const command = useFormCommand({
    form,
    fields: ['number', 'supplierId', 'warehouseId', 'expectedAt', 'lines'],
    mutation,
    toVariables: (intent: { body: PurchaseOrderRequest; idempotencyKey: string }) => intent,
    onDone,
  });
  const submit = ({ expectedAt, lines: values, ...order }: PurchaseOrderValues) =>
    command.send({
      ...order,
      ...(expectedAt ? { expectedAt } : {}),
      lines: values.map(({ unitCost, ...line }) => ({ ...line, ...(unitCost ? { unitCost } : {}) })),
    });
  const itemOptions = (items.data ?? []).map((item) => ({ value: item.id!, label: item.name! }));

  return (
    <CommandForm form={form} onSubmit={submit} command={command} pending={mutation.isPending} submitLabel="Criar encomenda" onCancel={onDone}>
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField control={form.control} name="number" label="Número" maxLength={30} />
        <TextField control={form.control} name="expectedAt" label="Entrega prevista" type="date" />
        <SelectField
          control={form.control}
          name="supplierId"
          label="Fornecedor"
          options={(suppliers.data?.rows ?? []).map((supplier) => ({ value: supplier.id!, label: supplier.name! }))}
        />
        <SelectField
          control={form.control}
          name="warehouseId"
          label="Armazém"
          options={(warehouses.data ?? []).map((warehouse) => ({ value: warehouse.id!, label: warehouse.name! }))}
        />
      </div>
      <fieldset className="flex flex-col gap-3">
        <legend className="mb-2 text-sm font-medium">Artigos</legend>
        {lines.fields.map((line, index) => (
          <div key={line.id} className="grid items-start gap-2 sm:grid-cols-12">
            <div className="sm:col-span-5">
              <SelectField control={form.control} name={`lines.${index}.stockItemId`} label={`Artigo ${index + 1}`} options={itemOptions} />
            </div>
            <div className="sm:col-span-3">
              <TextField control={form.control} name={`lines.${index}.quantity`} label={`Quantidade ${index + 1}`} inputMode="decimal" />
            </div>
            <div className="sm:col-span-3">
              <TextField control={form.control} name={`lines.${index}.unitCost`} label={`Custo unitário ${index + 1}`} inputMode="decimal" />
            </div>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="self-end"
              aria-label={`Retirar artigo ${index + 1}`}
              disabled={lines.fields.length === 1}
              onClick={() => lines.remove(index)}
            >
              <Trash2 aria-hidden="true" />
            </Button>
          </div>
        ))}
        <Button type="button" variant="outline" className="w-fit" onClick={() => lines.append(EMPTY_LINE)}>
          <Plus aria-hidden="true" />
          Acrescentar artigo
        </Button>
      </fieldset>
    </CommandForm>
  );
}
