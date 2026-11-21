import { useState } from 'react';
import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';
import { nextIntent, type Intent } from '../intent';
import { fieldErrors } from '../server-errors';

type Mutation<Variables> = {
  mutate: (variables: Variables, callbacks: { onSuccess: () => void; onError: (error: unknown) => void }) => void;
  error: unknown;
  isPending: boolean;
};

/**
 * A dialog form's command: each submit is an intent (one Idempotency-Key, kept while the body stays the same, FC §8);
 * a 400's violations land on the form's fields (the rest on the form); anything else is the general error, with
 * "Tentar novamente" resending the same intent.
 */
export function useFormCommand<Values extends FieldValues, Body, Variables>({
  form,
  fields,
  mutation,
  toVariables,
  onDone,
}: {
  form: { setError: UseFormSetError<Values> };
  fields: readonly Path<Values>[];
  mutation: Mutation<Variables>;
  toVariables: (intent: Intent<Body>) => Variables;
  onDone: () => void;
}) {
  const [intent, setIntent] = useState<Intent<Body>>();

  const send = (body: Body) => {
    const current = nextIntent(intent, body, () => crypto.randomUUID());
    setIntent(current);
    mutation.mutate(toVariables(current), {
      onSuccess: onDone,
      onError: (error) => {
        const found = fieldErrors(error, fields);
        if (!found) return;
        for (const [field, message] of found.fields) form.setError(field as Path<Values>, { message });
        if (found.others.length) form.setError('root' as Path<Values>, { message: found.others.join(' ') });
      },
    });
  };

  const error = mutation.error && !fieldErrors(mutation.error, fields) ? mutation.error : undefined;
  return { send, error, retry: () => intent && send(intent.body) };
}
