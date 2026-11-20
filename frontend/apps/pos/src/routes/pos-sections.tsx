import { NavLink } from 'react-router';

/** The POS's top-level sections, in Stitch's order; each is its own route and page. */
const SECTIONS = [
  { label: 'Sala & Mesas', to: '/tables' },
  { label: 'Takeaway & Delivery', to: '/delivery' },
  { label: 'Reservas', to: '/reservations' },
  { label: 'Caixa & Turno', to: '/cash' },
] as const;

/**
 * The section bar under the status bar (Stitch: equal tabs, the current one filled with the primary). Links, not tabs:
 * each section is a page. The current one is styled through `aria-current`, which NavLink sets.
 */
export function PosSections() {
  return (
    <nav aria-label="Secções" className="border-b bg-card px-4 py-2">
      <ul className="grid grid-cols-4 gap-2">
        {SECTIONS.map(({ label, to }) => (
          <li key={to}>
            <NavLink
              to={to}
              className="flex h-14 items-center justify-center rounded-lg px-4 text-center text-base font-semibold text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring aria-[current=page]:bg-primary aria-[current=page]:text-primary-foreground"
            >
              {label}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  );
}
