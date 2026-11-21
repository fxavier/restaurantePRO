import { useState } from 'react';
import type { FieldValues, Path, UseFormReturn } from 'react-hook-form';
import { fieldErrors, type TakenCodes } from '../server-errors';

/**
 * A configuration form's submit (FC §9): the button stays busy while `send` runs (`formState.isSubmitting`); an answer
 * about fields lands on them, anything else is `error`, for the form to explain.
 */
export function useCommandForm<In extends FieldValues, Out>(
  form: UseFormReturn<In, unknown, Out>,
  fields: readonly Path<In>[],
  taken?: TakenCodes<Path<In>>,
) {
  const [error, setError] = useState<unknown>();
  const submit = (send: (values: Out) => Promise<unknown>) =>
    form.handleSubmit(async (values) => {
      setError(undefined);
      try {
        await send(values);
      } catch (caught) {
        const shown = fieldErrors(caught, fields, taken);
        if (!shown) {
          setError(caught);
          return;
        }
        for (const [field, message] of Object.entries(shown.fields) as [Path<In>, string][]) form.setError(field, { message });
        if (shown.rest.length) form.setError('root', { message: shown.rest.join(' ') });
      }
    });
  return { error, submit };
}
