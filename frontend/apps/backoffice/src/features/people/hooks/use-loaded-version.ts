import type { UseMutationResult } from '@tanstack/react-query';
import { useState } from 'react';
import type { FieldValues, UseFormReturn } from 'react-hook-form';

/**
 * The version an edit form was loaded from, sent with the change so a newer one answers 409 VERSION_CONFLICT (FC §9).
 * `loadCurrent` puts the current row (the list refetches after every answer) into the form and starts again from it.
 */
export function useLoadedVersion<Values extends FieldValues, Variables, Result>(
  form: UseFormReturn<Values>,
  mutation: UseMutationResult<Result, Error, Variables>,
  current: { version?: number } | undefined,
  valuesOf: () => Values,
) {
  const [version, setVersion] = useState(current?.version ?? 0);
  const loadCurrent = () => {
    form.reset(valuesOf());
    setVersion(current?.version ?? 0);
    mutation.reset();
  };
  return { version, loadCurrent };
}
