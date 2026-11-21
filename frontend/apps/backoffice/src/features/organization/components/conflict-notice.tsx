import { Alert, AlertDescription } from '@restaurantpro/ui';
import { RefreshCw } from 'lucide-react';

/** After a 409 VERSION_CONFLICT the form holds the server's current values: say so, the user saves again. */
export function ConflictNotice({ shown, text }: { shown: boolean; text: string }) {
  if (!shown) return null;
  return (
    <Alert>
      <RefreshCw aria-hidden="true" />
      <AlertDescription>{text}</AlertDescription>
    </Alert>
  );
}
