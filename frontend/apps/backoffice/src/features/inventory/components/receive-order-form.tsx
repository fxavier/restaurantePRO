import { zodResolver } from '@hookform/resolvers/zod';
import { useRef } from 'react';
import { useForm } from 'react-hook-form';
import { formatQuantity } from '../format';
import { useFormCommand } from '../hooks/use-form-command';
import { useCreateReceipt } from '../hooks/use-inventory-commands';
import { useItemLookup } from '../hooks/use-inventory-reads';
import { receiptSchema, type ReceiptInput, type ReceiptValues } from '../schemas';
import type { PurchaseOrderView, ReceiptRequest } from '../types';
import { CommandForm, TextField } from './command-form';

/**
 * "Receber" a submitted order (§5 purchasing, §8.5): a draft goods receipt for its warehouse and supplier, each line
 * starting at what the server says is still outstanding (base unit), changed by the user to what actually arrived.
 * A line left empty or at zero did not arrive and is left out of the receipt (a partial delivery).
 */
export function ReceiveOrderForm({ order, onDone }: { order: PurchaseOrderView; onDone: () => void }) {
  const { item } = useItemLookup();
  const open = (order.lines ?? []).filter((line) => line.progress !== 'FULL');
  const form = useForm<ReceiptInput, unknown, ReceiptValues>({
    resolver: zodResolver(receiptSchema),
    defaultValues: {
      supplierDocumentRef: '',
      lines: open.map((line) => ({ purchaseOrderLineId: line.id!, stockItemId: line.stockItemId!, packQuantity: line.outstanding ?? '' })),
    },
  });
  const mutation = useCreateReceipt();
  /** The form line of each line sent, so the server's `lines[i]` lands on the line the user typed. */
  const sentLines = useRef<number[]>([]);
  const command = useFormCommand({
    form,
    fields: ['supplierDocumentRef', 'lines'],
    mutation,
    toVariables: (intent: { body: ReceiptRequest; idempotencyKey: string }) => intent,
    onDone,
    toFormField: (field) => field.replace(/^lines\[(\d+)\]/, (whole, index: string) => {
      const line = sentLines.current[Number(index)];
      return line === undefined ? whole : `lines[${line}]`;
    }),
  });
  const submit = ({ supplierDocumentRef, lines }: ReceiptValues) => {
    const received = lines.flatMap((line, index) =>
      line.packQuantity === undefined ? [] : [{ index, line: { ...line, packQuantity: line.packQuantity } }],
    );
    sentLines.current = received.map(({ index }) => index);
    command.send({
      warehouseId: order.warehouseId!,
      supplierId: order.supplierId!,
      purchaseOrderId: order.id!,
      ...(supplierDocumentRef ? { supplierDocumentRef } : {}),
      lines: received.map(({ line }) => line),
    });
  };

  return (
    <CommandForm form={form} onSubmit={submit} command={command} pending={mutation.isPending} submitLabel="Registar receção" onCancel={onDone}>
      <TextField control={form.control} name="supplierDocumentRef" label="Guia do fornecedor" maxLength={60} />
      <fieldset className="flex flex-col">
        <legend className="mb-2 text-sm font-medium">Recebido</legend>
        {/* One compact row per line, as the transfer receipt's. */}
        <div className="divide-y rounded-xl border">
          {open.map((line, index) => {
            const stockItem = item(line.stockItemId!);
            const name = stockItem?.name ?? '—';
            return (
              <div key={line.id} className="grid items-end gap-2 p-3 sm:grid-cols-2">
                <TextField
                  control={form.control}
                  name={`lines.${index}.packQuantity`}
                  label={`Recebido de ${name}`}
                  inputMode="decimal"
                />
                <p className="pb-2 text-sm text-muted-foreground tabular-nums sm:text-right">
                  Encomendado {formatQuantity(line.ordered, stockItem?.baseUnit)} · já recebido{' '}
                  {formatQuantity(line.received, stockItem?.baseUnit)}
                </p>
              </div>
            );
          })}
        </div>
      </fieldset>
    </CommandForm>
  );
}
