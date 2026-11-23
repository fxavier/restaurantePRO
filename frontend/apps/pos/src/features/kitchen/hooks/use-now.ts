import { useEffect, useState } from 'react';

/** The terminal's clock, read again every `everyMs`: enough for a display that counts whole minutes. */
export function useNow(everyMs = 15_000): Date {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), everyMs);
    return () => clearInterval(timer);
  }, [everyMs]);
  return now;
}
