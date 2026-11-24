import { zodResolver } from '@hookform/resolvers/zod';
import { cn, TONE_CARD } from '@restaurantpro/ui';
import { useForm, useWatch } from 'react-hook-form';
import { toDecimal, toInputDecimal } from '../decimal';
import { formatQuantity } from '../format';
import { useFormCommand } from '../hooks/use-form-command';
import { useReceiveTransfer } from '../hooks/use-inventory-commands';
import { useItemLookup } from '../hooks/use-inventory-reads';
import { arrivedShort, transferReceiptSchema, type TransferReceiptInput, type TransferReceiptValues } from '../schemas';
import type { ReceiveTransferRequest, TransferView } from '../types';
import { CommandForm, TextField } from './command-form';

/**
 * "Receber" a shipped transfer (D120, Q3 of the complete-app follow-ups): each line starts at what the server says was
 * shipped (base unit) and the user changes it to what arrived. When a line arrived short, the reason is asked: the
 * server posts the difference as a transfer loss with it. A full receipt sends the quantities as shipped, no reason.
 */
export function ReceiveTransferForm({ transfer, onDone }: { transfer: TransferView; onDone: () => void }) {
  const { item } = useItemLookup();
  const shipped = transfer.lines ?? [];
  const form = useForm<TransferReceiptInput, unknown, TransferReceiptValues>({
    resolver: zodResolver(transferReceiptSchema),
    defaultValues: {
      reason: '',
      lines: shipped.map((line) => ({
        stockItemId: line.stockItemId!,
        quantityShipped: line.quantityShipped!,
        quantityReceived: toInputDecimal(line.quantityShipped!),
      })),
    },
  });
  const typed = useWatch({ control: form.control, name: 'lines' });
  const short = typed.some((line) => arrivedShort(toDecimal(line.quantityReceived) ?? '', line.quantityShipped));
  const mutation = useReceiveTransfer();
  const command = useFormCommand({
    form,
    fields: ['lines', 'reason'],
    mutation,
    toVariables: (intent: { body: ReceiveTransferRequest; idempotencyKey: string }) => ({ ...intent, transferId: transfer.id! }),
    onDone,
  });
  const submit = ({ lines, reason }: TransferReceiptValues) => {
    const anyShort = lines.some((line) => arrivedShort(line.quantityReceived, line.quantityShipped));
    command.send({
      lines: lines.map(({ stockItemId, quantityReceived }) => ({ stockItemId, quantityReceived })),
      ...(anyShort ? { reason } : {}),
    });
  };

  return (
    <CommandForm form={form} onSubmit={submit} command={command} pending={mutation.isPending} submitLabel="Registar receção" onCancel={onDone}>
      <fieldset className="flex flex-col">
        <legend className="mb-2 text-sm font-medium">Recebido</legend>
        {/* The lines as the POS order's: one compact row each, what was shipped right-aligned and tabular. */}
        <div className="divide-y rounded-xl border">
          {shipped.map((line, index) => {
            const stockItem = item(line.stockItemId!);
            return (
              <div key={line.stockItemId} className="grid items-end gap-2 p-3 sm:grid-cols-2">
                <TextField
                  control={form.control}
                  name={`lines.${index}.quantityReceived`}
                  label={`Recebido de ${stockItem?.name ?? '—'}`}
                  inputMode="decimal"
                />
                <p className="pb-2 text-sm text-muted-foreground tabular-nums sm:text-right">
                  Enviado {formatQuantity(line.quantityShipped, stockItem?.baseUnit)}
                </p>
              </div>
            );
          })}
        </div>
      </fieldset>
      {short && (
        // Short: the amber of "a confirmar", the loss is posted with this reason; the field's label says why it shows.
        <div className={cn('rounded-xl border p-3', TONE_CARD.warning)}>
          <TextField control={form.control} name="reason" label="Motivo da diferença" maxLength={500} />
        </div>
      )}
    </CommandForm>
  );
}
