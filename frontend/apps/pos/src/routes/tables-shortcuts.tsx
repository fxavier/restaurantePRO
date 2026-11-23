import { useEffect, useRef } from 'react';

/** What a shortcut of `/tables` does; one absent from the props is not offered (the session may not). */
type Actions = {
  /** F2: the new reservation dialog. */
  reservation?: () => void;
  /** F3: the new takeaway dialog. */
  takeaway: () => void;
  /** "/": the table search. */
  search: () => void;
};

const KEYS = { F2: 'reservation', F3: 'takeaway', '/': 'search' } as const satisfies Record<string, keyof Actions>;

/**
 * The light reference's "F2 reserva · F3 takeaway · / pesquisa", on `/tables` only. A key typed into a field, or
 * inside a dialog, or with a modifier, is the field's or the dialog's: the shortcuts never take it.
 */
export function useTablesShortcuts(actions: Actions) {
  const latest = useRef(actions);
  useEffect(() => {
    latest.current = actions;
  });
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey || isOwnedByTarget(event.target)) return;
      const action = latest.current[KEYS[event.key as keyof typeof KEYS]];
      if (!action) return;
      event.preventDefault();
      action();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
}

function isOwnedByTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return (
    target.isContentEditable ||
    ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName) ||
    target.closest('[role="dialog"], [role="alertdialog"]') !== null
  );
}

/** The footer's hints, for the shortcuts this session has only. */
export function ShortcutHints({ reservation }: { reservation: boolean }) {
  const hints = [...(reservation ? [['F2', 'reserva']] : []), ['F3', 'takeaway'], ['/', 'procurar mesa']] as const;
  return (
    <p role="note" aria-label="Atalhos de teclado" className="ml-auto text-sm text-muted-foreground">
      {hints.map(([key, label], index) => (
        <span key={key}>
          {index > 0 && ' · '}
          <kbd className="font-sans font-semibold text-foreground">{key}</kbd> {label}
        </span>
      ))}
    </p>
  );
}
