import { zodResolver } from '@hookform/resolvers/zod';
import { Form } from '@restaurantpro/ui';
import { useForm } from 'react-hook-form';
import { useFormCommand } from '../hooks/use-form-command';
import { useResetPin } from '../hooks/use-people-commands';
import { resetPinSchema, type ResetPinValues } from '../schemas';
import type { EmployeeSummary } from '../types';
import { FormFooter } from './form-footer';
import { TextField } from './form-fields';

type ResetPinFormProps = { employee: EmployeeSummary; onDone: () => void; onClose: () => void };

/** "Repor PIN" (§5): the manager sets a new PIN; it lives only in this form while it is open. */
export function ResetPinForm({ employee, onDone, onClose }: ResetPinFormProps) {
  const reset = useResetPin();
  const form = useForm<ResetPinValues>({ resolver: zodResolver(resetPinSchema), defaultValues: { pin: '' } });
  const command = useFormCommand(form, reset, { onDone });
  const onSubmit = form.handleSubmit(({ pin }) => command.send({ employeeId: employee.id ?? '', pin }));

  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        <TextField
          control={form.control}
          name="pin"
          label="Novo PIN"
          type="password"
          inputMode="numeric"
          autoComplete="new-password"
          maxLength={8}
          className="max-w-48"
        />
        <FormFooter
          rootMessage={form.formState.errors.root?.message}
          generalError={command.generalError}
          pending={reset.isPending}
          onRetry={() => reset.variables && command.send(reset.variables)}
          onCancel={onClose}
          submitLabel="Repor PIN"
        />
      </form>
      {command.stepUpDialog}
    </Form>
  );
}
