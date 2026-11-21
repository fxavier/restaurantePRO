import { WifiOff } from 'lucide-react';
import { OFFLINE } from '../../../lib/error-messages';
import { refusal } from '../messages';

type Props = {
  /** "Sem ligação": nothing can be confirmed meanwhile (FC §11). */
  blocked: boolean;
  error: Error | undefined;
  /** The command as a feminine noun phrase ("a abertura do pedido"). */
  what: string;
};

/** A command's message line, its height kept so the buttons below never move when a message appears. */
export function CommandMessage({ blocked, error, what }: Props) {
  return (
    <div className="min-h-12">
      {blocked ? (
        <p className="flex items-center gap-2 font-medium text-error">
          <WifiOff aria-hidden="true" className="size-5 shrink-0" />
          {OFFLINE}
        </p>
      ) : (
        error && (
          <p role="alert" className="font-medium text-error">
            {refusal(error, what)}
          </p>
        )
      )}
    </div>
  );
}
