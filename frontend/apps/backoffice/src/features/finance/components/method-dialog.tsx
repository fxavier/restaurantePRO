import { zodResolver } from '@hookform/resolvers/zod';
import { ApiError } from '@restaurantpro/api-client';
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
import { useForm } from 'react-hook-form';
import { isStepUpRequired } from '../../../lib/step-up';
import type { FinanceCommand } from '../finance-commands';
import { FINANCE_MESSAGES } from '../finance-messages';
import { showOnFields } from '../form-errors';
import { useFinanceCommand } from '../hooks/use-finance-commands';
import { useFinanceStepUp } from '../hooks/use-finance-step-up';
import { METHOD_KINDS, methodSchema, type MethodValues } from '../schemas';
import type { PaymentMethodView } from '../types';
import { CommandAlert } from './command-alert';
import { selectClass } from './styles';

export const KIND_LABELS: Record<string, string> = {
  CASH: 'Dinheiro',
  EXTERNAL_TERMINAL: 'Terminal externo',
  INTEGRATED: 'Integrado',
};

/** `method` undefined: a new method; otherwise its edit, closed when `open` turns false. */
type Props = { storeId: string; open: boolean; method?: PaymentMethodView; onClose: () => void };

/** A company payment method (§7): code and kind are fixed once created; name, state and provider can change. */
export function MethodDialog({ storeId, open, method, onClose }: Props) {
  const title = method ? 'Editar meio de pagamento' : 'Novo meio de pagamento';
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>Os meios de pagamento valem para todas as lojas da empresa.</DialogDescription>
        </DialogHeader>
        {open && <MethodForm storeId={storeId} method={method} onDone={onClose} />}
      </DialogContent>
    </Dialog>
  );
}

const FIELDS = ['code', 'name', 'kind', 'providerCode', 'active'] as const;

function MethodForm({ storeId, method, onDone }: { storeId: string; method?: PaymentMethodView; onDone: () => void }) {
  const form = useForm<MethodValues>({
    resolver: zodResolver(methodSchema),
    defaultValues: {
      code: method?.code ?? '',
      name: method?.name ?? '',
      kind: (method?.kind as MethodValues['kind'] | undefined) ?? 'CASH',
      providerCode: method?.providerCode ?? '',
      active: method?.active ?? true,
    },
  });
  const save = useFinanceCommand();
  const stepUp = useFinanceStepUp(storeId);

  const send = (command: FinanceCommand) =>
    save.mutate(command, {
      onSuccess: onDone,
      onError: (error) => {
        if (isStepUpRequired(error)) stepUp.prompt(error, command);
        else if (error instanceof ApiError && error.code === 'PAYMENT_METHOD_CODE_TAKEN')
          form.setError('code', { message: FINANCE_MESSAGES.PAYMENT_METHOD_CODE_TAKEN });
        else showOnFields(form.setError, FIELDS, error);
      },
    });
  const onSubmit = form.handleSubmit(({ code, name, kind, providerCode, active }) => {
    const provider = providerCode ? { providerCode } : {};
    send(
      method
        ? { type: 'updateMethod', methodId: method.id!, body: { name, active, ...provider, version: method.version ?? 0 } }
        : { type: 'createMethod', body: { code, name, kind, ...provider } },
    );
  });
  const errors = form.formState.errors;
  const onField = FIELDS.some((field) => errors[field]) || errors.root;
  const shown = save.error && !isStepUpRequired(save.error) && !onField;

  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        {!method && (
          <div className="grid gap-4 sm:grid-cols-2">
            <FormField
              control={form.control}
              name="code"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Código</FormLabel>
                  <FormControl>
                    <Input {...field} autoComplete="off" maxLength={30} className="uppercase" />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField
              control={form.control}
              name="kind"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Tipo</FormLabel>
                  <FormControl>
                    <select {...field} className={selectClass}>
                      {METHOD_KINDS.map((kind) => (
                        <option key={kind} value={kind}>
                          {KIND_LABELS[kind]}
                        </option>
                      ))}
                    </select>
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
          </div>
        )}
        <FormField
          control={form.control}
          name="name"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Nome</FormLabel>
              <FormControl>
                <Input {...field} autoComplete="off" maxLength={120} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={form.control}
          name="providerCode"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Fornecedor (opcional)</FormLabel>
              <FormControl>
                <Input {...field} autoComplete="off" maxLength={60} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        {method && (
          <FormField
            control={form.control}
            name="active"
            render={({ field }) => (
              <FormItem className="flex flex-row items-center gap-2">
                <FormControl>
                  <input
                    type="checkbox"
                    className="size-5 accent-primary"
                    checked={field.value}
                    onChange={(event) => field.onChange(event.target.checked)}
                    onBlur={field.onBlur}
                    name={field.name}
                    ref={field.ref}
                  />
                </FormControl>
                <FormLabel>Ativo</FormLabel>
              </FormItem>
            )}
          />
        )}
        {errors.root?.message && (
          <p role="alert" className="text-sm text-error">
            {errors.root.message}
          </p>
        )}
        {shown && <CommandAlert error={save.error} pending={save.isPending} onRetry={() => save.variables && send(save.variables)} />}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onDone}>
            Cancelar
          </Button>
          <ConfirmButton type="submit" pending={save.isPending}>
            Guardar
          </ConfirmButton>
        </DialogFooter>
      </form>
      {stepUp.dialog}
    </Form>
  );
}
