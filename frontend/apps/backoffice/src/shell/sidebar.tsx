import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@restaurantpro/ui';
import { UtensilsCrossed } from 'lucide-react';
import { useId } from 'react';
import { NavLink } from 'react-router';
import { useSession } from '../features/identity';
import { storeSearch, useStoreSelection } from '../features/organization';
import { visibleAreas, type AreaGroup } from './navigation';

/**
 * The areas' card (light reference, as the POS section bar): white bordered pills, the current one solid green. 256 px
 * with labels from 1280 px, 80 px icons below (DS layouts). The labels stay in the accessible name when only
 * the icon shows, and a tooltip names the icon for a pointer user. Links carry `?storeId` from route to route. The
 * active link is styled through `aria-current`: the tooltip trigger's Slot would stringify a className function.
 */
export function Sidebar() {
  const { data: session } = useSession();
  const { storeId } = useStoreSelection();
  const search = storeSearch(storeId);
  const groups = visibleAreas(session?.permissions ?? []);

  return (
    <aside className="flex w-20 shrink-0 flex-col self-start rounded-2xl border bg-card xl:w-64">
      <div className="flex min-h-16 items-center justify-center gap-2 border-b px-3 py-2 xl:justify-start xl:px-4">
        {/* The brand block: the mark on the primary, the product and "BackOffice" beside it. */}
        <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary text-primary-foreground">
          <UtensilsCrossed aria-hidden="true" className="size-5" />
        </span>
        <span className="sr-only flex-col xl:not-sr-only xl:flex">
          <span className="text-base leading-tight font-semibold text-foreground">restaurantPRO</span>
          <span className="text-xs font-semibold tracking-wide text-primary uppercase">BackOffice</span>
        </span>
      </div>
      <TooltipProvider>
        <nav aria-label="Áreas" className="flex flex-col gap-4 p-3">
          {groups.map((group, index) => (
            <AreaList key={group.label ?? index} group={group} search={search} />
          ))}
        </nav>
      </TooltipProvider>
    </aside>
  );
}

function AreaList({ group, search }: { group: AreaGroup; search: string }) {
  const headingId = useId();
  return (
    <div className={group.label ? 'flex flex-col gap-1 border-t pt-3 xl:border-t-0 xl:pt-0' : 'flex flex-col gap-1'}>
      {group.label && (
        <p id={headingId} className="sr-only px-3 text-xs font-medium tracking-wide text-muted-foreground uppercase xl:not-sr-only">
          {group.label}
        </p>
      )}
      <ul aria-labelledby={group.label ? headingId : undefined} className="flex flex-col gap-1">
        {group.areas.map(({ label, to, icon: Icon, end }) => (
          <li key={to}>
            <Tooltip>
              <TooltipTrigger asChild>
                <NavLink
                  to={{ pathname: to, search }}
                  end={end}
                  className="flex h-12 items-center justify-center gap-3 rounded-xl border bg-card px-3 text-sm font-medium text-foreground outline-none hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-card aria-[current=page]:border-primary aria-[current=page]:bg-primary aria-[current=page]:font-semibold aria-[current=page]:text-primary-foreground xl:justify-start"
                >
                  <Icon aria-hidden="true" className="size-5 shrink-0" />
                  <span className="sr-only xl:not-sr-only">{label}</span>
                </NavLink>
              </TooltipTrigger>
              <TooltipContent side="right" className="xl:hidden">
                {label}
              </TooltipContent>
            </Tooltip>
          </li>
        ))}
      </ul>
    </div>
  );
}
