import { Button } from '@restaurantpro/ui';
import { CircleUser, LoaderCircle, Users, Wifi, WifiOff } from 'lucide-react';
import { useSyncExternalStore } from 'react';
import { useEmployee } from '../lib/auth/use-employee';
import { useEndSession } from '../lib/auth/use-end-session';
import { useStreamState } from '../lib/live/live-updates';
import { useServices } from '../lib/services';

/**
 * The POS status bar (DS layouts): the employee, the connection, and "Trocar empregado". "Ligado" means the change
 * stream is open, so the screen is current; "Sem ligação" that it is down (the screen is polled) or that Keycloak is.
 */
export function StatusBar() {
  const { tokens } = useServices();
  const employee = useEmployee();
  const endSession = useEndSession();
  const tokenStatus = useSyncExternalStore(tokens.subscribe, tokens.status);
  const stream = useStreamState();
  const connection = tokenStatus === 'unavailable' || stream === 'down' ? 'down' : stream;

  return (
    <header className="flex h-16 items-center gap-6 border-b bg-background px-4">
      <h1 className="text-lg font-semibold">restaurantPRO — Ponto de venda</h1>
      <p className="flex items-center gap-2 font-medium">
        <CircleUser aria-hidden="true" className="size-6" />
        <span className="sr-only">Empregado:</span>
        {employee?.name}
      </p>
      {/* A fixed width, so the button beside it never moves when the state changes (DS: no layout shift). */}
      <p role="status" aria-label="Ligação" className="flex w-40 items-center gap-2 text-muted-foreground">
        {connection === 'open' ? (
          <Wifi aria-hidden="true" className="size-6" />
        ) : connection === 'down' ? (
          <WifiOff aria-hidden="true" className="size-6 text-destructive" />
        ) : (
          <LoaderCircle aria-hidden="true" className="size-6" />
        )}
        {connection === 'open' ? 'Ligado' : connection === 'down' ? 'Sem ligação' : 'A ligar…'}
      </p>
      <Button variant="outline" size="touch" className="ml-auto" onClick={() => endSession('switch')}>
        <Users aria-hidden="true" />
        Trocar empregado
      </Button>
    </header>
  );
}
