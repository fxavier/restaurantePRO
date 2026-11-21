import { useEffect, useState } from 'react';
import { storeDate } from '../store-time';

/** How often a screen left open looks at the clock: the day turns over within this of the store's midnight. */
const TICK_MS = 30_000;

/** The store's day now (`YYYY-MM-DD`), turning over at the store's midnight while the screen stays open. */
export function useStoreToday(timeZone: string): string {
  const [today, setToday] = useState(() => storeDate(new Date(), timeZone));
  useEffect(() => {
    const timer = setInterval(() => setToday(storeDate(new Date(), timeZone)), TICK_MS);
    return () => clearInterval(timer);
  }, [timeZone]);
  return today;
}
