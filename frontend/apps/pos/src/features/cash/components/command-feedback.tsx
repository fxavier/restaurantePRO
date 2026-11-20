import { WifiOff } from 'lucide-react';
import { OFFLINE } from '../../../lib/error-messages';
import { cashRefusal } from '../messages';

type Props = {
  /** "Sem ligação": the command cannot be confirmed now (FC §11). */
  blocked: boolean;
  error: Error | undefined;
  /** What the command is, for an outcome the screen must call unconfirmed ("a abertura da sessão"). */
  what: string;
};

/**
 * The line under a cash command: "Sem ligação" while the POS is offline, else the server's refusal. Its height is kept
 * when empty, so the buttons below never move when a message appears.
 */
export function CommandFeedback({ blocked, error, what }: Props) {
  return (
    <div className="min-h-12">
      {blocked ? (
        <p className="flex items-center gap-2 font-medium text-error">
          <WifiOff aria-hidden="true" className="size-5 shrink-0" />
          {OFFLINE}
        </p>
      ) : error ? (
        <p role="alert" className="font-medium text-error">
          {cashRefusal(error, what)}
        </p>
      ) : null}
    </div>
  );
}
