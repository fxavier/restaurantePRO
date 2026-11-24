import { Alert, AlertDescription } from '@restaurantpro/ui';
import { Outlet } from 'react-router';
import { SessionGate, TenantSwitcher, useTenantChangeNotice, UserMenu } from '../features/identity';
import { StoreSelect } from '../features/organization';
import { Sidebar } from './sidebar';

/**
 * The signed-in BackOffice (DS layouts, DESIGN.md "Light reference" as the POS shows it): the grey page with white
 * rounded cards: the areas' card on the left, the top bar card (company · store · user, as the POS status bar's store ·
 * terminal · employee), and the content up to 1280 px.
 */
export function AppShell() {
  const tenantChanged = useTenantChangeNotice();
  return (
    <SessionGate>
      <a
        href="#conteudo"
        className="sr-only z-50 rounded-xl bg-card px-4 py-2 font-medium focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:ring-2 focus:ring-ring"
      >
        Saltar para o conteúdo
      </a>
      <div className="flex min-h-screen gap-4 p-4">
        <Sidebar />
        <div className="flex min-w-0 flex-1 flex-col gap-4">
          <header className="flex min-h-16 flex-wrap items-center gap-x-6 gap-y-2 rounded-2xl border bg-card px-4 py-2">
            <TenantSwitcher />
            <StoreSelect />
            <div className="ml-auto">
              <UserMenu />
            </div>
          </header>
          <main id="conteudo" tabIndex={-1} className="flex-1 outline-none">
            <div className="mx-auto max-w-7xl">
              {tenantChanged && (
                <Alert role="status" className="mb-6 rounded-2xl">
                  <AlertDescription>{tenantChanged}</AlertDescription>
                </Alert>
              )}
              <Outlet />
            </div>
          </main>
        </div>
      </div>
    </SessionGate>
  );
}
