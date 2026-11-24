import { Tabs, TabsContent, TabsList, TabsTrigger } from '@restaurantpro/ui';
import { Lock } from 'lucide-react';
import type { ReactNode } from 'react';
import { useSearchParams } from 'react-router';
import { useSession } from '../../identity';
import { selectedTab, visibleTabs, type PeopleTabId } from '../tabs';
import { AccountsTab } from './accounts-tab';
import { EmployeesTab } from './employees-tab';
import { RolesTab } from './roles-tab';
import { SupportTab } from './support-tab';

const PARAM = 'tab';

const content: Record<PeopleTabId, () => ReactNode> = {
  empregados: () => <EmployeesTab />,
  funcoes: () => <RolesTab />,
  utilizadores: () => <AccountsTab />,
  suporte: () => <SupportTab />,
};

/**
 * Stitch "Utilizadores, Empregados & RBAC": the page header, then one tab per kind of identity, the open one in
 * `?tab=` (so a reload or a link keeps it, beside `?storeId`). A tab shows only with its permission; the API enforces.
 */
export function PeopleScreen() {
  const session = useSession();
  const [params, setParams] = useSearchParams();
  const tabs = visibleTabs(session.data?.permissions ?? []);
  const current = selectedTab(params.get(PARAM), tabs);

  const open = (id: string) =>
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous);
        next.set(PARAM, id);
        return next;
      },
      { replace: true },
    );

  return (
    <>
      <p className="w-fit rounded-full bg-success-tint px-2.5 py-0.5 text-xs font-semibold tracking-wide text-success-text uppercase">
        Identidade & Acessos
      </p>
      <h1 className="mt-2 text-2xl font-semibold">Utilizadores, Empregados & Funções</h1>
      <p className="mt-1 text-muted-foreground">
        Os empregados e os seus PIN, as funções e as suas permissões por loja, e as contas do BackOffice.
      </p>
      {current ? (
        <Tabs value={current} onValueChange={open} className="mt-6 gap-4">
          <TabsList className="h-auto flex-wrap">
            {tabs.map((tab) => (
              <TabsTrigger key={tab.id} value={tab.id} className="min-h-9 px-4">
                {tab.label}
              </TabsTrigger>
            ))}
          </TabsList>
          {tabs.map((tab) => (
            <TabsContent key={tab.id} value={tab.id}>
              {tab.id === current && content[tab.id]()}
            </TabsContent>
          ))}
        </Tabs>
      ) : (
        <div className="mt-6 flex flex-col items-center gap-3 rounded-xl border bg-card p-8 text-center">
          <Lock aria-hidden="true" className="size-8 text-muted-foreground" />
          <p>Sem permissão para gerir empregados, funções ou utilizadores.</p>
        </div>
      )}
    </>
  );
}
