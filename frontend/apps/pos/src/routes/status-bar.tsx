import { Button } from '@restaurantpro/ui';
import { CircleUser, Users, Wifi, WifiOff } from 'lucide-react';
import { useSyncExternalStore } from 'react';
import { useEmployee } from '../lib/auth/use-employee';
import { useEndSession } from '../lib/auth/use-end-session';
import { useServices } from '../lib/services';

/** The POS status bar (DS layouts): the employee, the connection, and "Trocar empregado". */
export function StatusBar() {
  const { tokens } = useServices();
  const employee = useEmployee();
  const endSession = useEndSession();
  const online = useSyncExternalStore(tokens.subscribe, tokens.status) !== 'unavailable';

  return (
    <header className="flex h-16 items-center gap-6 border-b bg-background px-4">
      <h1 className="text-lg font-semibold">restaurantPRO — Ponto de venda</h1>
      <p className="flex items-center gap-2 font-medium">
        <CircleUser aria-hidden="true" className="size-6" />
        <span className="sr-only">Empregado:</span>
        {employee?.name}
      </p>
      <p className="flex items-center gap-2 text-muted-foreground">
        {online ? <Wifi aria-hidden="true" className="size-6" /> : <WifiOff aria-hidden="true" className="size-6" />}
        {online ? 'Ligado' : 'Sem ligação'}
      </p>
      <Button variant="outline" size="touch" className="ml-auto" onClick={() => endSession('switch')}>
        <Users aria-hidden="true" />
        Trocar empregado
      </Button>
    </header>
  );
}
