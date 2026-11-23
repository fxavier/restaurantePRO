import { NavLink } from 'react-router';
import { mayWatchKitchen } from '../features/kitchen/kitchen';
import { useEmployee } from '../lib/auth/use-employee';

/** The POS's top-level sections, in Stitch's order; each is its own route and page. */
const SECTIONS = [
  { label: 'Sala & Mesas', to: '/tables' },
  { label: 'Takeaway & Delivery', to: '/delivery' },
  { label: 'Reservas', to: '/reservations' },
  { label: 'Caixa & Turno', to: '/cash' },
] as const;

/** The kitchen display, only for a session that may watch it (`PRODUCTION_VIEW`). */
const KITCHEN = { label: 'KDS', to: '/kds' } as const;

/**
 * The section bar under the status bar: equal links, the current one filled with the primary (as the light
 * reference's selected area tab). Links, not tabs: each section is a page. The current one is styled through
 * `aria-current`, which NavLink sets.
 */
export function PosSections() {
  const employee = useEmployee();
  const sections = mayWatchKitchen(employee) ? [...SECTIONS, KITCHEN] : SECTIONS;
  return (
    <nav aria-label="Secções">
      <ul className="grid auto-cols-fr grid-flow-col gap-2">
        {sections.map(({ label, to }) => (
          <li key={to}>
            <NavLink
              to={to}
              className="flex h-14 items-center justify-center rounded-xl border bg-card px-4 text-center text-base font-semibold text-foreground outline-none hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background aria-[current=page]:border-primary aria-[current=page]:bg-primary aria-[current=page]:text-primary-foreground"
            >
              {label}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  );
}
