import { Card, Tabs, TabsContent, TabsList, TabsTrigger } from '@restaurantpro/ui';
import { ArrowLeftRight, Boxes, ChartColumn, ClipboardList, CookingPot, ShoppingCart, type LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { useSearchParams } from 'react-router';
import { AREA_TAB, AREA_TABS_LIST } from '../../../shell/area-tabs';
import { useSession } from '../../identity';
import { useStore } from '../hooks/use-inventory-reads';
import { holds } from '../permissions';
import { CountsTab } from './counts-tab';
import { PurchasingTab } from './purchasing-tab';
import { RecipesTab } from './recipes-tab';
import { ReportsTab } from './reports-tab';
import { StockSummary } from './stock-summary';
import { StockTab } from './stock-tab';
import { TransfersTab } from './transfers-tab';

type Store = { storeId: string; currency: string | undefined; timeZone: string | undefined };
/** `permissions`: any one of them reads the tab (backend `access.Permission`), for choosing the landing tab. */
type Tab = { value: string; label: string; icon: LucideIcon; permissions: readonly string[]; render: (store: Store) => ReactNode };

const TABS: Tab[] = [
  {
    value: 'stock',
    label: 'Stock',
    icon: Boxes,
    permissions: ['INVENTORY_VIEW'],
    render: ({ storeId, currency }) => <StockTab storeId={storeId} currency={currency} />,
  },
  {
    value: 'contagens',
    label: 'Contagens',
    icon: ClipboardList,
    permissions: ['INVENTORY_VIEW'],
    render: ({ storeId }) => <CountsTab storeId={storeId} />,
  },
  {
    value: 'receitas',
    label: 'Fichas técnicas',
    icon: CookingPot,
    permissions: ['RECIPE_MANAGE'],
    render: ({ storeId }) => <RecipesTab storeId={storeId} />,
  },
  {
    value: 'compras',
    label: 'Compras',
    icon: ShoppingCart,
    // Suppliers: PURCHASE_MANAGE; orders: INVENTORY_VIEW; receipts: requireAny(GOODS_RECEIVE, INVENTORY_VIEW).
    permissions: ['INVENTORY_VIEW', 'GOODS_RECEIVE', 'PURCHASE_MANAGE'],
    render: ({ storeId, timeZone }) => <PurchasingTab storeId={storeId} timeZone={timeZone} />,
  },
  {
    value: 'transferencias',
    label: 'Transferências',
    icon: ArrowLeftRight,
    permissions: ['INVENTORY_VIEW'],
    render: ({ storeId, timeZone }) => <TransfersTab storeId={storeId} timeZone={timeZone} />,
  },
  {
    value: 'relatorios',
    label: 'Relatórios',
    icon: ChartColumn,
    permissions: ['INVENTORY_VIEW'],
    render: ({ storeId, currency }) => <ReportsTab storeId={storeId} currency={currency} />,
  },
];
const PARAM = 'tab';

/**
 * The selected store's inventory (§5, Stitch "Stocks, Receitas & Compras"); the tab is `?tab=`, so a link keeps it.
 * Without one, the first tab the user may open in the store (a convenience, the server decides, §10); with none of
 * them, the first tab and its denial.
 */
export function InventoryScreen({ storeId }: { storeId: string }) {
  const store = useStore(storeId);
  const permissions = useSession().data?.permissions ?? [];
  const [params, setParams] = useSearchParams();
  const requested = params.get(PARAM);
  const landing = TABS.find((candidate) => candidate.permissions.some((name) => holds(permissions, name, storeId))) ?? TABS[0]!;
  const tab = TABS.some(({ value }) => value === requested) ? requested! : landing.value;
  const context: Store = { storeId, currency: store.data?.currency, timeZone: store.data?.timezone };
  const select = (value: string) =>
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous);
        next.set(PARAM, value);
        return next;
      },
      { replace: true },
    );

  return (
    <div className="mt-6 flex flex-col gap-6">
      <StockSummary storeId={storeId} currency={context.currency} />
      <Tabs value={tab} onValueChange={select} className="gap-4">
        <Card className="p-2">
          <TabsList className={AREA_TABS_LIST}>
            {TABS.map(({ value, label, icon: Icon }) => (
              <TabsTrigger key={value} value={value} className={AREA_TAB}>
                <Icon aria-hidden="true" />
                {label}
              </TabsTrigger>
            ))}
          </TabsList>
        </Card>
        {TABS.map(({ value, render }) => (
          <TabsContent key={value} value={value} className="min-w-0">
            {render(context)}
          </TabsContent>
        ))}
      </Tabs>
    </div>
  );
}
