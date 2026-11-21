import type { UseMutationResult } from '@tanstack/react-query';
import type { FieldPath, FieldValues, UseFormReturn } from 'react-hook-form';
import { isStepUpRequired } from '../../../lib/step-up';
import { fieldErrors } from '../field-errors';
import { usePeopleStepUp } from './use-people-step-up';

type Options = {
  /** The answer codes that belong to one field, e.g. a taken code on `code`. */
  conflicts?: Readonly<Record<string, string>>;
  onDone?: () => void;
};

/**
 * Sends a form's command and places its answer (FC §9): a step-up opens its dialog, a field error lands on its field,
 * anything else is `generalError` for the form to explain. `send(variables)` again is the same request (FC §8).
 */
export function useFormCommand<Values extends FieldValues, Variables, Result>(
  form: UseFormReturn<Values>,
  mutation: UseMutationResult<Result, Error, Variables>,
  { conflicts = {}, onDone }: Options = {},
) {
  const stepUp = usePeopleStepUp();
  const fields = Object.keys(form.getValues());

  const send = (variables: Variables) =>
    mutation.mutate(variables, {
      onSuccess: () => onDone?.(),
      onError: (error) => {
        if (isStepUpRequired(error)) return stepUp.prompt(error);
        for (const { field, message } of fieldErrors(error, fields, conflicts) ?? []) {
          form.setError(field as FieldPath<Values>, { message });
        }
      },
    });

  const generalError =
    mutation.error && !isStepUpRequired(mutation.error) && !fieldErrors(mutation.error, fields, conflicts)
      ? mutation.error
      : null;

  return { send, generalError, stepUpDialog: stepUp.dialog };
}
