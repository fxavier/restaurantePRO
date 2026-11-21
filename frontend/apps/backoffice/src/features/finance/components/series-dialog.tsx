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
import { Plus } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { isStepUpRequired } from '../../../lib/step-up';
import type { FinanceCommand } from '../finance-commands';
import { showOnFields } from '../form-errors';
import { useFinanceCommand } from '../hooks/use-finance-commands';
import { useFinanceStepUp } from '../hooks/use-finance-step-up';
import { seriesSchema, type SeriesValues } from '../schemas';
import { CommandAlert } from './command-alert';

/** "Nova série": the store's sale series, with the number its documents start at (spec §8.4 numbering). */
export function SeriesDialog({ storeId }: { storeId: string }) {
  const [open, setOpen] = useState(false);
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <Button onClick={() => setOpen(true)}>
        <Plus aria-hidden="true" />
        Nova série
      </Button>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Nova série</DialogTitle>
          <DialogDescription>A loja emite os documentos de venda na sua série ativa, por ordem e sem falhas.</DialogDescription>
        </DialogHeader>
        {open && <SeriesForm storeId={storeId} onDone={() => setOpen(false)} />}
      </DialogContent>
    </Dialog>
  );
}

function SeriesForm({ storeId, onDone }: { storeId: string; onDone: () => void }) {
  const form = useForm<SeriesValues>({ resolver: zodResolver(seriesSchema), defaultValues: { code: '', firstNumber: '' } });
  const create = useFinanceCommand();
  const stepUp = useFinanceStepUp(storeId);

  const send = (command: FinanceCommand) =>
    create.mutate(command, {
      onSuccess: onDone,
      onError: (error) => {
        if (isStepUpRequired(error)) stepUp.prompt(error, command);
        else showOnFields(form.setError, ['code', 'firstNumber'], error);
      },
    });
  const onSubmit = form.handleSubmit(({ code, firstNumber }) =>
    send({ type: 'createSeries', body: { storeId, code, ...(firstNumber ? { firstNumber: Number(firstNumber) } : {}) } }),
  );
  const rootMessage = form.formState.errors.root?.message;
  const shown = create.error && !isStepUpRequired(create.error) && !form.formState.errors.code && !rootMessage;

  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        <FormField
          control={form.control}
          name="code"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Código</FormLabel>
              <FormControl>
                <Input {...field} autoComplete="off" maxLength={20} className="max-w-48 uppercase" />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={form.control}
          name="firstNumber"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Primeiro número</FormLabel>
              <FormControl>
                <Input {...field} inputMode="numeric" autoComplete="off" maxLength={9} className="max-w-48" />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        {rootMessage && (
          <p role="alert" className="text-sm text-error">
            {rootMessage}
          </p>
        )}
        {shown && <CommandAlert error={create.error} pending={create.isPending} onRetry={() => create.variables && send(create.variables)} />}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onDone}>
            Cancelar
          </Button>
          <ConfirmButton type="submit" pending={create.isPending}>
            Criar série
          </ConfirmButton>
        </DialogFooter>
      </form>
      {stepUp.dialog}
    </Form>
  );
}
