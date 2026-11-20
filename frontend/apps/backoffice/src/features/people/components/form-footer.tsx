import { Button, ConfirmButton } from '@restaurantpro/ui';
import { CommandError } from './command-error';

type FormFooterProps = {
  /** The form's `root` error: the server's violations that name no field of the form. */
  rootMessage: string | undefined;
  generalError: Error | null;
  pending: boolean;
  /** Sends the same request again after "Sem ligação" (FC §8). */
  onRetry: () => void;
  onCancel: () => void;
  submitLabel: string;
};

/** The end of a people form: the form-level and general errors, then "Cancelar" and the one-tap submit. */
export function FormFooter({ rootMessage, generalError, pending, onRetry, onCancel, submitLabel }: FormFooterProps) {
  return (
    <>
      {rootMessage && (
        <p role="alert" className="text-sm text-error">
          {rootMessage}
        </p>
      )}
      <CommandError error={generalError} pending={pending} onRetry={onRetry} />
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
