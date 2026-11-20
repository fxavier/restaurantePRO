import { WifiOff } from 'lucide-react';
import { OFFLINE } from '../../../lib/error-messages';

type Props = {
  /** "Sem ligação": the command cannot be confirmed now (FC §11). */
  blocked: boolean;
  /** Why the last attempt failed (`cashRefusal`), if it did. */
  error: string | undefined;
};

/**
 * The line under a cash command: "Sem ligação" while the POS is offline, else the server's refusal. Its height is kept
 * when empty, so the buttons below never move when a message appears.
 */
export function CommandFeedback({ blocked, error }: Props) {
  return (
    <div className="min-h-12">
      {blocked ? (
        <p className="flex items-center gap-2 font-medium text-error">
          <WifiOff aria-hidden="true" className="size-5 shrink-0" />
          {OFFLINE}
        </p>
      ) : error ? (
        <p role="alert" className="font-medium text-error">
          {error}
        </p>
      ) : null}
    </div>
  );
}
