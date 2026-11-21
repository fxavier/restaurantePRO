import { Tabs, TabsContent, TabsList, TabsTrigger } from '@restaurantpro/ui';
import { ArrowLeftRight, Boxes, ChartColumn, ClipboardList, CookingPot, ShoppingCart, type LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { useSearchParams } from 'react-router';
import { useStore } from '../hooks/use-inventory-reads';
import { CountsTab } from './counts-tab';
import { PurchasingTab } from './purchasing-tab';
import { RecipesTab } from './recipes-tab';
import { ReportsTab } from './reports-tab';
import { StockSummary } from './stock-summary';
import { StockTab } from './stock-tab';
import { TransfersTab } from './transfers-tab';

type Store = { storeId: string; currency: string | undefined; timeZone: string | undefined };
type Tab = { value: string; label: string; icon: LucideIcon; render: (store: Store) => ReactNode };

const TABS: Tab[] = [
  { value: 'stock', label: 'Stock', icon: Boxes, render: ({ storeId, currency }) => <StockTab storeId={storeId} currency={currency} /> },
  { value: 'contagens', label: 'Contagens', icon: ClipboardList, render: ({ storeId }) => <CountsTab storeId={storeId} /> },
  { value: 'receitas', label: 'Fichas técnicas', icon: CookingPot, render: ({ storeId }) => <RecipesTab storeId={storeId} /> },
  {
    value: 'compras',
    label: 'Compras',
    icon: ShoppingCart,
    render: ({ storeId, timeZone }) => <PurchasingTab storeId={storeId} timeZone={timeZone} />,
  },
  {
    value: 'transferencias',
    label: 'Transferências',
    icon: ArrowLeftRight,
    render: ({ storeId, timeZone }) => <TransfersTab storeId={storeId} timeZone={timeZone} />,
  },
  {
    value: 'relatorios',
    label: 'Relatórios',
    icon: ChartColumn,
    render: ({ storeId, currency }) => <ReportsTab storeId={storeId} currency={currency} />,
  },
];
const PARAM = 'tab';

/** The selected store's inventory (§5, Stitch "Stocks, Receitas & Compras"); the tab is `?tab=`, so a link keeps it. */
export function InventoryScreen({ storeId }: { storeId: string }) {
  const store = useStore(storeId);
  const [params, setParams] = useSearchParams();
  const requested = params.get(PARAM);
  const tab = TABS.some(({ value }) => value === requested) ? requested! : TABS[0]!.value;
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
        <TabsList className="h-auto flex-wrap">
          {TABS.map(({ value, label, icon: Icon }) => (
            <TabsTrigger key={value} value={value} className="min-h-9">
              <Icon aria-hidden="true" />
              {label}
            </TabsTrigger>
          ))}
        </TabsList>
        {TABS.map(({ value, render }) => (
          <TabsContent key={value} value={value}>
            {render(context)}
          </TabsContent>
        ))}
      </Tabs>
    </div>
  );
}
