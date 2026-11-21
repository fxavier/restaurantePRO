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
import { showOnFields } from '../form-errors';
import { useSensitiveCommand } from '../hooks/use-sensitive-command';
import { seriesSchema, type SeriesValues } from '../schemas';
import { CommandAlert } from './command-alert';
import { FormProblem } from './form-problem';

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

const FIELDS = ['code', 'firstNumber'] as const;

function SeriesForm({ storeId, onDone }: { storeId: string; onDone: () => void }) {
  const form = useForm<SeriesValues>({ resolver: zodResolver(seriesSchema), defaultValues: { code: '', firstNumber: '' } });
  const create = useSensitiveCommand(storeId);
  const onSubmit = form.handleSubmit(({ code, firstNumber }) =>
    create.send(
      { type: 'createSeries', body: { storeId, code, ...(firstNumber ? { firstNumber: Number(firstNumber) } : {}) } },
      { onSuccess: onDone, onError: (error) => showOnFields(form.setError, FIELDS, error) },
    ),
  );
  const onField = FIELDS.some((field) => form.formState.errors[field]) || form.formState.errors.root;

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
        <FormProblem message={form.formState.errors.root?.message} />
        {create.failure && !onField && <CommandAlert {...create.failure} />}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onDone}>
            Cancelar
          </Button>
          <ConfirmButton type="submit" pending={create.pending}>
            Criar série
          </ConfirmButton>
        </DialogFooter>
      </form>
      {create.dialog}
    </Form>
  );
}
