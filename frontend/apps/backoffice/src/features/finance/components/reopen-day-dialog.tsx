import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button,
  ConfirmButton,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
  Input,
} from '@restaurantpro/ui';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import type { CashDay } from '../finance-labels';
import { formatBusinessDate } from '../format';
import { useReopenDay, type Reopening } from '../hooks/use-finance-commands';
import { reopenDaySchema, type ReopenDayValues } from '../schemas';
import { CommandAlert } from './command-alert';
import { textareaClass } from './styles';

/** Opened for one day; the form mounts per opening, so each opening is a new intent with a new key. */
export function ReopenDayDialog({ day, onClose }: { day: CashDay | null; onClose: () => void }) {
  const date = formatBusinessDate(day?.businessDate);
  return (
    <Dialog open={day !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Reabrir o dia {date}</DialogTitle>
          <DialogDescription>
            A reabertura fica registada na auditoria com o motivo e o aprovador, um empregado com permissão para aprovar
            fechos nesta loja.
          </DialogDescription>
        </DialogHeader>
        {day && <ReopenForm dayId={day.id} onDone={onClose} />}
      </DialogContent>
    </Dialog>
  );
}

function ReopenForm({ dayId, onDone }: { dayId: string; onDone: () => void }) {
  const form = useForm<ReopenDayValues>({
    resolver: zodResolver(reopenDaySchema),
    defaultValues: { reason: '', approverCode: '', approverPin: '' },
  });
  const reopen = useReopenDay();
  // One intent while the inputs stay the same (FC §8); changed inputs are a new intent.
  const [intent, setIntent] = useState<Reopening | undefined>();

  const send = (values: ReopenDayValues) => {
    const same =
      intent &&
      intent.reason === values.reason &&
      intent.approverCode === values.approverCode &&
      intent.approverPin === values.approverPin;
    const current = same ? intent : { dayId, ...values, idempotencyKey: crypto.randomUUID() };
    setIntent(current);
    reopen.mutate(current, { onSuccess: onDone });
  };
  const onSubmit = form.handleSubmit(send);

  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        <FormField
          control={form.control}
          name="reason"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Motivo</FormLabel>
              <FormControl>
                <textarea {...field} maxLength={500} className={textareaClass} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <div className="grid gap-4 sm:grid-cols-2">
          <FormField
            control={form.control}
            name="approverCode"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Código do aprovador</FormLabel>
                <FormControl>
                  <Input {...field} autoComplete="off" maxLength={20} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
          <FormField
            control={form.control}
            name="approverPin"
            render={({ field }) => (
              <FormItem>
                <FormLabel>PIN do aprovador</FormLabel>
                <FormControl>
                  <Input {...field} type="password" inputMode="numeric" autoComplete="off" maxLength={8} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
        </div>
        {reopen.error && (
          <CommandAlert error={reopen.error} pending={reopen.isPending} onRetry={() => intent && send(intent)} />
        )}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onDone}>
            Cancelar
          </Button>
          <ConfirmButton type="submit" pending={reopen.isPending}>
            Reabrir
          </ConfirmButton>
        </DialogFooter>
      </form>
    </Form>
  );
}
