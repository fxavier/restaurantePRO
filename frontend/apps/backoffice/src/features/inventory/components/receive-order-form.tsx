import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { formatQuantity } from '../format';
import { useFormCommand } from '../hooks/use-form-command';
import { useCreateReceipt } from '../hooks/use-inventory-commands';
import { useStockItems } from '../hooks/use-inventory-reads';
import { receiptSchema, type ReceiptInput, type ReceiptValues } from '../schemas';
import type { PurchaseOrderView, ReceiptRequest } from '../types';
import { CommandForm, TextField } from './command-form';

/**
 * "Receber" a submitted order (§5 purchasing, §8.5): a draft goods receipt for its warehouse and supplier, each line
 * starting at what the server says is still outstanding (base unit), changed by the user to what actually arrived.
 */
export function ReceiveOrderForm({ order, onDone }: { order: PurchaseOrderView; onDone: () => void }) {
  const items = useStockItems();
  const open = (order.lines ?? []).filter((line) => line.progress !== 'FULL');
  const form = useForm<ReceiptInput, unknown, ReceiptValues>({
    resolver: zodResolver(receiptSchema),
    defaultValues: {
      supplierDocumentRef: '',
      lines: open.map((line) => ({ purchaseOrderLineId: line.id!, stockItemId: line.stockItemId!, packQuantity: line.outstanding ?? '' })),
    },
  });
  const mutation = useCreateReceipt();
  const command = useFormCommand({
    form,
    fields: ['supplierDocumentRef', 'lines'],
    mutation,
    toVariables: (intent: { body: ReceiptRequest; idempotencyKey: string }) => intent,
    onDone,
  });
  const submit = ({ supplierDocumentRef, lines }: ReceiptValues) =>
    command.send({
      warehouseId: order.warehouseId!,
      supplierId: order.supplierId!,
      purchaseOrderId: order.id!,
      ...(supplierDocumentRef ? { supplierDocumentRef } : {}),
      lines,
    });
  const item = (id: string) => items.data?.find((candidate) => candidate.id === id);

  return (
    <CommandForm form={form} onSubmit={submit} command={command} pending={mutation.isPending} submitLabel="Registar receção" onCancel={onDone}>
      <TextField control={form.control} name="supplierDocumentRef" label="Guia do fornecedor" maxLength={60} />
      <fieldset className="flex flex-col gap-3">
        <legend className="mb-2 text-sm font-medium">Recebido</legend>
        {open.map((line, index) => {
          const stockItem = item(line.stockItemId!);
          const name = stockItem?.name ?? '—';
          return (
            <div key={line.id} className="grid items-end gap-2 sm:grid-cols-2">
              <TextField
                control={form.control}
                name={`lines.${index}.packQuantity`}
                label={`Recebido de ${name}`}
                inputMode="decimal"
              />
              <p className="pb-2 text-sm text-muted-foreground">
                Encomendado {formatQuantity(line.ordered, stockItem?.baseUnit)} · já recebido{' '}
                {formatQuantity(line.received, stockItem?.baseUnit)}
              </p>
            </div>
          );
        })}
      </fieldset>
    </CommandForm>
  );
}
