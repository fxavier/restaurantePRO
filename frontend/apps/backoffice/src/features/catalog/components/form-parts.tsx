import {
  Alert,
  AlertDescription,
  Button,
  ConfirmButton,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
  Input,
  NativeSelect,
} from '@restaurantpro/ui';
import { TriangleAlert, WifiOff } from 'lucide-react';
import type { Control, FieldPath, FieldValues, UseFormReturn } from 'react-hook-form';
import { isTenantChanged, isUnavailable } from '../../../lib/errors';
import { isInProgress } from '../../../lib/in-progress';
import { catalogErrorMessage, placeOnFields } from '../form-errors';

type FieldProps<Values extends FieldValues> = {
  control: Control<Values>;
  name: FieldPath<Values>;
  label: string;
};

export function TextField<Values extends FieldValues>({
  control,
  name,
  label,
  maxLength,
  inputMode,
  className,
}: FieldProps<Values> & { maxLength?: number; inputMode?: 'decimal'; className?: string }) {
  return (
    <FormField
      control={control}
      name={name}
      render={({ field }) => (
        <FormItem>
          <FormLabel>{label}</FormLabel>
          <FormControl>
            <Input {...field} value={(field.value as string | undefined) ?? ''} autoComplete="off" maxLength={maxLength} inputMode={inputMode} className={className} />
          </FormControl>
          <FormMessage />
        </FormItem>
      )}
    />
  );
}

export type Option = { value: string; label: string };

/** A native select: keyboard and screen-reader behaviour for free, styled with the field tokens. */
export function SelectField<Values extends FieldValues>({
  control,
  name,
  label,
  options,
  placeholder,
}: FieldProps<Values> & { options: readonly Option[]; placeholder: string }) {
  return (
    <FormField
      control={control}
      name={name}
      render={({ field }) => (
        <FormItem>
          <FormLabel>{label}</FormLabel>
          <FormControl>
            <NativeSelect {...field} value={(field.value as string | undefined) ?? ''}>
              <option value="">{placeholder}</option>
              {options.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </NativeSelect>
          </FormControl>
          <FormMessage />
        </FormItem>
      )}
    />
  );
}

/**
 * Puts a server answer where it belongs (FC §9): field violations and a taken code on their fields, the rest of a
 * 400 as a form-level message. True when the answer was a field error.
 */
export function showOnFields<Values extends FieldValues>(form: UseFormReturn<Values>, error: unknown, names: readonly FieldPath<Values>[]): boolean {
  const placement = placeOnFields(error, names);
  if (!placement) return false;
  for (const { name, message } of placement.fields) form.setError(name as FieldPath<Values>, { message });
  if (placement.rest.length) form.setError('root', { message: placement.rest.join(' ') });
  return true;
}

type FormFooterProps = {
  submitLabel: string;
  pending: boolean;
  /** The command's failure that is not on a field, if any. */
  error: unknown;
  rootMessage: string | undefined;
  onCancel: () => void;
  onRetry: () => void;
};

/** The form's general errors, then Cancelar and the submit button (disabled while in flight). */
export function FormFooter({ submitLabel, pending, error, rootMessage, onCancel, onRetry }: FormFooterProps) {
  return (
    <>
      {rootMessage && (
        <p role="alert" className="text-sm text-error">
          {rootMessage}
        </p>
      )}
      {error !== null && error !== undefined && <CommandAlert error={error} pending={pending} onRetry={onRetry} />}
      <div className="flex flex-wrap justify-end gap-2">
        <Button type="button" variant="outline" onClick={onCancel}>
          Cancelar
        </Button>
        <ConfirmButton type="submit" pending={pending}>
          {submitLabel}
        </ConfirmButton>
      </div>
    </>
  );
}

/**
 * A command's failure (FC §9): explained in Portuguese; without an answer the outcome is unknown, so "Tentar novamente"
 * sends the same request again (FC §8). A command a resend could duplicate passes no `onRetry`. TENANT_CHANGED is not
 * explained: the page reloads for it.
 */
export function CommandAlert({ error, pending, onRetry }: { error: unknown; pending: boolean; onRetry?: () => void }) {
  if (isTenantChanged(error)) return null;
  const unavailable = isUnavailable(error);
  const Icon = unavailable ? WifiOff : TriangleAlert;
  return (
    <Alert variant="destructive">
      <Icon aria-hidden="true" />
      <AlertDescription className="flex flex-wrap items-center gap-3">
        <span>{catalogErrorMessage(error)}</span>
        {onRetry && (unavailable || isInProgress(error)) && (
          <Button type="button" variant="outline" size="sm" onClick={onRetry} disabled={pending}>
            Tentar novamente
          </Button>
        )}
      </AlertDescription>
    </Alert>
  );
}
