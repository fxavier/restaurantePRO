import { Button } from '@restaurantpro/ui';
import { Building2, LoaderCircle, LogIn, Lock, TriangleAlert, WifiOff } from 'lucide-react';
import type { ReactNode } from 'react';
import { errorCode, isSessionExpired, isUnavailable } from '../../../lib/errors';
import { useLogout } from '../hooks/use-logout';
import { useSession, useTenants } from '../hooks/use-session';
import type { TenantChoice } from '../types';
import { FullPage } from './full-page';
import { LogoutFailed } from './logout-failed';
import { TenantChooser } from './tenant-chooser';

const blocked: Record<string, { title: string; text: string }> = {
  TENANT_SUSPENDED: {
    title: 'Empresa suspensa',
    text: 'O acesso a esta empresa está suspenso. Contacte o suporte restaurantPRO.',
  },
  TENANT_CLOSED: {
    title: 'Empresa encerrada',
    text: 'Esta empresa foi encerrada e já não pode ser usada.',
  },
};

/**
 * Renders the shell only once the user is signed in with a usable company (FC §6, §9): otherwise the login redirect,
 * the company choice, a blocked company, "Sem ligação" or a generic error, each as a full page.
 */
export function SessionGate({ children }: { children: ReactNode }) {
  const session = useSession();
  const tenants = useTenants();
  const error = session.error ?? tenants.error;
  const retry = () => {
    void session.refetch();
    void tenants.refetch();
  };

  if (error && isSessionExpired(error)) {
    return (
      <FullPage icon={LogIn} title="Sessão terminada">
        <p role="status">A redirecionar para o início de sessão…</p>
      </FullPage>
    );
  }
  if (error && isUnavailable(error)) {
    return (
      <FullPage icon={WifiOff} title="Sem ligação">
        <p>Nenhuma operação é confirmada enquanto o serviço não responder.</p>
        <Button onClick={retry} disabled={session.isFetching || tenants.isFetching}>
          Tentar novamente
        </Button>
      </FullPage>
    );
  }
  const code = errorCode(error);
  const page = code ? blocked[code] : undefined;
  if (page) {
    return (
      <FullPage icon={Lock} title={page.title}>
        <p>{page.text}</p>
        <OtherCompanies tenants={tenants.data} />
        <LogoutButton />
      </FullPage>
    );
  }
  if (code === 'TENANT_SELECTION_REQUIRED' || code === 'TENANT_FORBIDDEN') {
    if (tenants.isPending) return <Loading />;
    if (tenants.data?.length) {
      return (
        <FullPage icon={Building2} title="Escolha a empresa">
          <p>Pertence a várias empresas. Escolha aquela em que vai trabalhar.</p>
          <TenantChooser tenants={tenants.data} />
        </FullPage>
      );
    }
    return (
      <FullPage icon={Lock} title="Sem acesso">
        <p>A sua conta não tem acesso a nenhuma empresa.</p>
        <LogoutButton />
      </FullPage>
    );
  }
  if (error) {
    return (
      <FullPage icon={TriangleAlert} title="Não foi possível carregar">
        <p>Ocorreu um erro inesperado.</p>
        <Button onClick={retry} disabled={session.isFetching || tenants.isFetching}>
          Tentar novamente
        </Button>
      </FullPage>
    );
  }
  if (session.isPending || tenants.isPending) return <Loading />;
  return children;
}

function Loading() {
  return (
    <main className="grid min-h-screen place-items-center">
      <p role="status" className="flex items-center gap-2 text-muted-foreground">
        <LoaderCircle aria-hidden="true" className="size-5 animate-spin" />A carregar…
      </p>
    </main>
  );
}

function OtherCompanies({ tenants }: { tenants?: TenantChoice[] }) {
  if (!tenants || tenants.length < 2) return null;
  return (
    <>
      <h2 className="text-lg font-semibold">Escolher outra empresa</h2>
      <TenantChooser tenants={tenants} />
    </>
  );
}

function LogoutButton() {
  const logout = useLogout();
  return (
    <>
      <Button variant="outline" disabled={logout.isPending} onClick={() => logout.mutate()}>
        Terminar sessão
      </Button>
      {logout.isError && <LogoutFailed />}
    </>
  );
}
