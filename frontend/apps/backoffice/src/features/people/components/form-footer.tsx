import { Button, ConfirmButton } from '@restaurantpro/ui';
import { errorCode } from '../../../lib/errors';
import { CommandError } from './command-error';

type FormFooterProps = {
  /** The form's `root` error: the server's violations that name no field of the form. */
  rootMessage: string | undefined;
  generalError: Error | null;
  pending: boolean;
  /** Sends the same request again after "Sem ligação" (FC §8). */
  onRetry: () => void;
  onCancel: () => void;
  cancelLabel?: string;
  submitLabel: string;
  /** An edit form's way out of a 409 VERSION_CONFLICT: load the current version, then save against it. */
  onLoadCurrent?: () => void;
};

/** The end of a people form: the form-level and general errors, then "Cancelar" and the one-tap submit. */
export function FormFooter({
  rootMessage,
  generalError,
  pending,
  onRetry,
  onCancel,
  cancelLabel = 'Cancelar',
  submitLabel,
  onLoadCurrent,
}: FormFooterProps) {
  return (
    <>
      {rootMessage && (
        <p role="alert" className="text-sm text-error">
          {rootMessage}
        </p>
      )}
      <CommandError error={generalError} pending={pending} onRetry={onRetry} />
      {onLoadCurrent && errorCode(generalError) === 'VERSION_CONFLICT' && (
        <Button type="button" variant="outline" className="self-start" onClick={onLoadCurrent}>
          Carregar a versão atual
        </Button>
      )}
      <div className="flex flex-wrap justify-end gap-2">
        <Button type="button" variant="outline" onClick={onCancel}>
          {cancelLabel}
        </Button>
        <ConfirmButton type="submit" pending={pending}>
          {submitLabel}
        </ConfirmButton>
      </div>
    </>
  );
}
