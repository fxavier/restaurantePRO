import { WifiOff } from 'lucide-react';

/** Shown while the backend, the BFF or Keycloak cannot be reached (§11, FC §11); confirmations are disabled meanwhile. */
export function ConnectionBanner() {
  return (
    <div role="alert" className="flex items-center gap-2 bg-destructive px-4 py-2 font-medium text-destructive-foreground">
      <WifiOff aria-hidden="true" className="size-5 shrink-0" />
      Sem ligação — nenhuma operação é confirmada
    </div>
  );
}
