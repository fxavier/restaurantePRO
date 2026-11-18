import { useEffect } from 'react';

/** FC §6 step 4: the terminal returns to the PIN pad after 90 s without a touch or a key. */
export const IDLE_LOGOUT_MS = 90_000;

const INPUT = ['pointerdown', 'keydown', 'wheel', 'touchstart'] as const;

/** Calls `onIdle` once `ms` pass without input anywhere on the page; any input starts the wait again. */
export function useIdle(onIdle: () => void, ms = IDLE_LOGOUT_MS) {
  useEffect(() => {
    let timer = setTimeout(onIdle, ms);
    const restart = () => {
      clearTimeout(timer);
      timer = setTimeout(onIdle, ms);
    };
    for (const type of INPUT) document.addEventListener(type, restart, { capture: true, passive: true });
    return () => {
      clearTimeout(timer);
      for (const type of INPUT) document.removeEventListener(type, restart, { capture: true });
    };
  }, [onIdle, ms]);
}
