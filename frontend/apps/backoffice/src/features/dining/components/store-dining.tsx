import { DayReservationsCard } from './day-reservations-card';
import { DiningSettingsCard } from './dining-settings-card';
import { StoreFloor } from './store-floor';

/** A store's dining configuration (§5 "Atendimento"): areas and tables, reservation settings, the day's reservations. */
export function StoreDining({ storeId }: { storeId: string }) {
  return (
    <>
      <StoreFloor storeId={storeId} />
      <div className="mt-8 grid gap-4 xl:grid-cols-5">
        <div className="xl:col-span-2">
          <DiningSettingsCard storeId={storeId} />
        </div>
        <div className="xl:col-span-3">
          <DayReservationsCard storeId={storeId} />
        </div>
      </div>
    </>
  );
}
