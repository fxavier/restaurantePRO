import { Alert, AlertDescription } from '@restaurantpro/ui';
import { Outlet } from 'react-router';
import { SessionGate, TenantSwitcher, useTenantChangeNotice, UserMenu } from '../features/identity';
import { StoreSelect } from '../features/organization';
import { Sidebar } from './sidebar';

/** The signed-in BackOffice (DS layouts): sidebar, top bar with company, store and user, content up to 1280 px. */
export function AppShell() {
  const tenantChanged = useTenantChangeNotice();
  return (
    <SessionGate>
      <a
        href="#conteudo"
        className="sr-only z-50 rounded-md bg-background px-4 py-2 font-medium focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:ring-2 focus:ring-ring"
      >
        Saltar para o conteúdo
      </a>
      <div className="flex min-h-screen">
        <Sidebar />
        <div className="flex min-w-0 flex-1 flex-col">
          <header className="flex h-14 items-center gap-6 border-b px-6">
            <TenantSwitcher />
            <StoreSelect />
            <div className="ml-auto">
              <UserMenu />
            </div>
          </header>
          <main id="conteudo" tabIndex={-1} className="flex-1 px-6 py-6 outline-none">
            <div className="mx-auto max-w-7xl">
              {tenantChanged && (
                <Alert role="status" className="mb-6">
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
