import { zodResolver } from '@hookform/resolvers/zod';
import { Button, Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from '@restaurantpro/ui';
import { PackageMinus, SlidersHorizontal } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useFormCommand } from '../hooks/use-form-command';
import { useRecordMovement, type MovementKind } from '../hooks/use-inventory-commands';
import { useStockItems } from '../hooks/use-inventory-reads';
import { movementSchema, wasteSchema, type MovementInput, type MovementValues } from '../schemas';
import type { ManualMovementRequest } from '../types';
import { CommandForm, SelectField, TextField } from './command-form';

const copy = {
  waste: {
    title: 'Registar quebra',
    description: 'Sai do armazém o que se estragou, partiu ou perdeu.',
    icon: PackageMinus,
    schema: wasteSchema,
  },
  'manual-adjustments': {
    title: 'Ajustar stock',
    description: 'Uma quantidade positiva entra (com o custo unitário), uma negativa sai.',
    icon: SlidersHorizontal,
    schema: movementSchema,
  },
} as const;

/** "Registar quebra" and "Ajustar stock" (§5 inventory): one item of the warehouse; mounted only while open. */
export function MovementDialog({ kind, warehouseId }: { kind: MovementKind; warehouseId: string }) {
  const [open, setOpen] = useState(false);
  const { title, description, icon: Icon } = copy[kind];
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant={kind === 'waste' ? 'outline' : 'default'}>
          <Icon aria-hidden="true" />
          {title}
        </Button>
      </DialogTrigger>
      <DialogContent showCloseButton={false}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <MovementForm kind={kind} warehouseId={warehouseId} onDone={() => setOpen(false)} />
      </DialogContent>
    </Dialog>
  );
}

function MovementForm({ kind, warehouseId, onDone }: { kind: MovementKind; warehouseId: string; onDone: () => void }) {
  const items = useStockItems();
  const form = useForm<MovementInput, unknown, MovementValues>({
    resolver: zodResolver(copy[kind].schema),
    defaultValues: { stockItemId: '', quantity: '', unitCost: '', reason: '' },
  });
  const mutation = useRecordMovement();
  const command = useFormCommand({
    form,
    fields: ['stockItemId', 'quantity', 'unitCost', 'reason'],
    mutation,
    toVariables: (intent: { body: ManualMovementRequest; idempotencyKey: string }) => ({ ...intent, kind }),
    onDone,
  });
  const submit = ({ stockItemId, quantity, unitCost, reason }: MovementValues) =>
    command.send({
      warehouseId,
      ...(reason ? { reason } : {}),
      lines: [{ stockItemId, quantity, ...(unitCost ? { unitCost } : {}) }],
    });

  return (
    <CommandForm form={form} onSubmit={submit} command={command} pending={mutation.isPending} submitLabel="Registar" onCancel={onDone}>
      <SelectField
        control={form.control}
        name="stockItemId"
        label="Artigo"
        options={(items.data ?? []).map((item) => ({ value: item.id!, label: item.name! }))}
      />
      <TextField control={form.control} name="quantity" label="Quantidade" inputMode="decimal" />
      {kind === 'manual-adjustments' && (
        <TextField control={form.control} name="unitCost" label="Custo unitário (entradas)" inputMode="decimal" />
      )}
      <TextField control={form.control} name="reason" label="Motivo" maxLength={500} />
    </CommandForm>
  );
}
