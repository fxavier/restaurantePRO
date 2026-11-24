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
  NativeSelect,
} from '@restaurantpro/ui';
import { useForm, type UseFormReturn } from 'react-hook-form';
import type { FinanceCommand } from '../finance-commands';
import { FINANCE_MESSAGES } from '../finance-messages';
import { isMaybeCreated, MAYBE_CREATED, showOnFields } from '../form-errors';
import { useSensitiveCommand } from '../hooks/use-sensitive-command';
import { METHOD_KINDS, methodSchema, type MethodValues } from '../schemas';
import type { PaymentMethodView } from '../types';
import { CommandAlert } from './command-alert';
import { FormProblem } from './form-problem';

export const KIND_LABELS: Record<string, string> = {
  CASH: 'Dinheiro',
  EXTERNAL_TERMINAL: 'Terminal externo',
  INTEGRATED: 'Integrado',
};

/** `method` undefined: a new method; otherwise its edit, closed when `open` turns false. */
type Props = { storeId: string; open: boolean; method?: PaymentMethodView; onClose: () => void };

/** A company payment method (§7): code and kind are fixed once created; name, state and provider can change. */
export function MethodDialog({ storeId, open, method, onClose }: Props) {
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{method ? 'Editar meio de pagamento' : 'Novo meio de pagamento'}</DialogTitle>
          <DialogDescription>Os meios de pagamento valem para todas as lojas da empresa.</DialogDescription>
        </DialogHeader>
        {open && <MethodForm storeId={storeId} method={method} onDone={onClose} />}
      </DialogContent>
    </Dialog>
  );
}

const FIELDS = ['code', 'name', 'kind', 'providerCode', 'active'] as const;
type MethodFormApi = UseFormReturn<MethodValues>;

const BLANK: MethodValues = { code: '', name: '', kind: 'CASH', providerCode: '', active: true };

/** The form's values: a new method's blanks, or what the edited method holds (what it does not send stays blank). */
function defaults(method: PaymentMethodView | undefined): MethodValues {
  const { code, name, kind, providerCode, active } = method ?? {};
  const held = Object.entries({ code, name, kind, providerCode, active }).filter(([, value]) => value != null);
  return { ...BLANK, ...(Object.fromEntries(held) as Partial<MethodValues>) };
}

/** The request the form's values make: a create, or an update under the version the edit read (optimistic lock). */
function toCommand(values: MethodValues, method: PaymentMethodView | undefined): FinanceCommand {
  const { code, name, kind, providerCode, active } = values;
  const provider = providerCode ? { providerCode } : {};
  if (!method) return { type: 'createMethod', body: { code, name, kind, ...provider } };
  return { type: 'updateMethod', methodId: method.id!, body: { name, active, ...provider, version: method.version ?? 0 } };
}

/** A taken code goes on the code field; a 400's violations on theirs; a create's conflict after a lost answer is explained. */
function showError(form: MethodFormApi, error: Error, maybeCreated: boolean) {
  if (maybeCreated) {
    form.setError('root', { message: MAYBE_CREATED });
    return;
  }
  if (error instanceof ApiError && error.code === 'PAYMENT_METHOD_CODE_TAKEN') {
    form.setError('code', { message: FINANCE_MESSAGES.PAYMENT_METHOD_CODE_TAKEN });
    return;
  }
  showOnFields(form.setError, FIELDS, error);
}

function MethodForm({ storeId, method, onDone }: { storeId: string; method?: PaymentMethodView; onDone: () => void }) {
  const form = useForm<MethodValues>({ resolver: zodResolver(methodSchema), defaultValues: defaults(method) });
  const save = useSensitiveCommand(storeId);
  const onSubmit = form.handleSubmit((values) =>
    save.send(toCommand(values, method), {
      onSuccess: onDone,
      onError: (error, afterUnknown) => showError(form, error, !method && isMaybeCreated(error, afterUnknown)),
    }),
  );
  const errors = form.formState.errors;
  const onField = FIELDS.some((field) => errors[field]) || errors.root;

  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        {!method && <CreateFields form={form} />}
        <TextField form={form} name="name" label="Nome" maxLength={120} />
        <TextField form={form} name="providerCode" label="Fornecedor (opcional)" maxLength={60} />
        {method && <ActiveField form={form} />}
        <FormProblem message={errors.root?.message} />
        {save.failure && !onField && <CommandAlert {...save.failure} />}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onDone}>
            Cancelar
          </Button>
          <ConfirmButton type="submit" pending={save.pending}>
            Guardar
          </ConfirmButton>
        </DialogFooter>
      </form>
      {save.dialog}
    </Form>
  );
}

function TextField({ form, name, label, maxLength }: { form: MethodFormApi; name: 'code' | 'name' | 'providerCode'; label: string; maxLength: number }) {
  return (
    <FormField
      control={form.control}
      name={name}
      render={({ field }) => (
        <FormItem>
          <FormLabel>{label}</FormLabel>
          <FormControl>
            <Input {...field} autoComplete="off" maxLength={maxLength} />
          </FormControl>
          <FormMessage />
        </FormItem>
      )}
    />
  );
}

/** Code and kind are chosen once, when the method is created. */
function CreateFields({ form }: { form: MethodFormApi }) {
  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <TextField form={form} name="code" label="Código" maxLength={30} />
      <FormField
        control={form.control}
        name="kind"
        render={({ field }) => (
          <FormItem>
            <FormLabel>Tipo</FormLabel>
            <FormControl>
              <NativeSelect {...field} className="h-10 w-auto">
                {METHOD_KINDS.map((kind) => (
                  <option key={kind} value={kind}>
                    {KIND_LABELS[kind]}
                  </option>
                ))}
              </NativeSelect>
            </FormControl>
            <FormMessage />
          </FormItem>
        )}
      />
    </div>
  );
}

function ActiveField({ form }: { form: MethodFormApi }) {
  return (
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
  );
}
