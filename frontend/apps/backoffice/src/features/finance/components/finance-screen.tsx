import { useState } from 'react';
import { useStore } from '../hooks/use-store';
import { lastDays } from '../period';
import { SummaryTab } from './summary-tab';

/** The selected store's finance area (§5): its tabs over one period, in the store's timezone. */
export function FinanceScreen({ storeId }: { storeId: string }) {
  const store = useStore(storeId);
  const [days] = useState(7);
  if (!store) return null;
  const period = lastDays(days, new Date(), store.timezone);
  return (
    <div className="mt-6 flex flex-col gap-6">
      <SummaryTab storeId={storeId} period={period} />
    </div>
  );
}
