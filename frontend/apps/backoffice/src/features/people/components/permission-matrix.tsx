import { StatusPill } from '@restaurantpro/ui';
import { ShieldAlert } from 'lucide-react';
import { useId } from 'react';
import type { ReadState } from '../hooks/use-choices';
import type { PermissionArea } from '../permission-areas';
import { permissionLabel } from '../permission-labels';
import type { PermissionView } from '../types';
import { ChoicesError } from './choices-error';

type PermissionMatrixProps = {
  /** The permission catalogue by area, as the server answered it. */
  catalogue: ReadState & { areas: PermissionArea[] };
  value: readonly string[];
  onChange: (value: string[]) => void;
};

/**
 * "Matriz granular de privilégios" (Stitch): every permission a role can grant, grouped by area, each a switch. The
 * value keeps the names it holds that the catalogue does not list, so saving never drops them unseen.
 */
export function PermissionMatrix({ catalogue, value, onChange }: PermissionMatrixProps) {
  const titleId = useId();
  const toggle = (name: string, on: boolean) => onChange(on ? [...value, name] : value.filter((held) => held !== name));

  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-3">
      <div className="flex items-baseline justify-between gap-2">
        <h3 id={titleId} className="text-sm font-semibold tracking-wide uppercase">
          Matriz de permissões
        </h3>
        <StatusPill tone="success">{value.length} ativas</StatusPill>
      </div>
      <CatalogueNotice catalogue={catalogue} />
      {/* The page's grey under the areas' white cards, as the POS lays its cards out. */}
      <div className="grid max-h-96 grid-cols-1 items-start gap-3 overflow-y-auto rounded-xl bg-background p-3 md:grid-cols-2">
        {catalogue.areas.map((area) => (
          <AreaGroup key={area.area} area={area} granted={value} onToggle={toggle} />
        ))}
      </div>
    </section>
  );
}

/** Why the matrix has no switches: the catalogue is still loading, its read failed, or it lists nothing. */
function CatalogueNotice({ catalogue }: Pick<PermissionMatrixProps, 'catalogue'>) {
  if (catalogue.pending) return <p className="text-sm text-muted-foreground">A carregar as permissões…</p>;
  if (catalogue.error) return <ChoicesError list={catalogue} />;
  if (catalogue.areas.length > 0) return null;
  return <p className="text-sm text-muted-foreground">Não há permissões para conceder.</p>;
}

type AreaGroupProps = { area: PermissionArea; granted: readonly string[]; onToggle: (name: string, on: boolean) => void };

/** One area's permissions, under its name and how many of them are on. */
function AreaGroup({ area, granted, onToggle }: AreaGroupProps) {
  const id = useId();
  const on = area.permissions.filter((permission) => granted.includes(permission.name)).length;
  const total = area.permissions.length;
  return (
    <div role="group" aria-labelledby={id} className="flex flex-col gap-1 rounded-xl border bg-card p-2">
      <div className="flex items-center justify-between gap-2 border-b px-2 pb-2">
        <h4 id={id} className="text-sm font-semibold">
          {area.label}
        </h4>
        <StatusPill tone={on > 0 ? 'success' : 'neutral'} className="text-xs tabular-nums">
          <span aria-hidden="true">{`${on} / ${total}`}</span>
          <span className="sr-only">{`${on} de ${total} ativas`}</span>
        </StatusPill>
      </div>
      <ul className="flex flex-col">
        {area.permissions.map((permission) => (
          <li key={permission.name}>
            <PermissionSwitch
              permission={permission}
              checked={granted.includes(permission.name)}
              onChange={(on) => onToggle(permission.name, on)}
            />
          </li>
        ))}
      </ul>
    </div>
  );
}

type PermissionSwitchProps = { permission: PermissionView; checked: boolean; onChange: (on: boolean) => void };

/**
 * A permission's switch, named by its Portuguese label with the stored code as secondary text (a code this BackOffice
 * has no label for is named by the code alone); the knob's position says on or off besides its colour. A sensitive
 * permission (D142: a BackOffice account needs a fresh second factor to use it) carries a badge and says why.
 */
function PermissionSwitch({ permission, checked, onChange }: PermissionSwitchProps) {
  const id = useId();
  const label = permissionLabel(permission.name);
  const described = [label && `${id}-code`, permission.sensitive && `${id}-badge ${id}-why`].filter(Boolean).join(' ');
  return (
    <label className="flex min-h-12 cursor-pointer items-center justify-between gap-3 rounded-lg px-2 py-1 hover:bg-muted">
      <span className="flex flex-col gap-0.5">
        <span className="flex flex-wrap items-center gap-2">
          {label ? (
            <span className="flex flex-col">
              <span id={`${id}-name`} className="text-sm">
                {label}
              </span>
              <span id={`${id}-code`} className="font-mono text-xs text-muted-foreground">
                {permission.name}
              </span>
            </span>
          ) : (
            <span id={`${id}-name`} className="font-mono text-sm">
              {permission.name}
            </span>
          )}
          {permission.sensitive && (
            <StatusPill id={`${id}-badge`} tone="warning" icon={ShieldAlert} className="text-xs">
              Sensível
            </StatusPill>
          )}
        </span>
        {permission.sensitive && (
          <span id={`${id}-why`} className="text-xs text-muted-foreground">
            Usá-la exige confirmar a identidade com o segundo fator.
          </span>
        )}
      </span>
      <span className="relative inline-flex shrink-0 items-center">
        <input
          type="checkbox"
          role="switch"
          aria-labelledby={`${id}-name`}
          aria-describedby={described || undefined}
          checked={checked}
          onChange={(event) => onChange(event.target.checked)}
          className="peer h-6 w-11 cursor-pointer appearance-none rounded-full border border-input bg-muted transition-colors checked:border-primary checked:bg-primary focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-card focus-visible:outline-none"
        />
        <span
          aria-hidden="true"
          className="pointer-events-none absolute left-1 size-4 rounded-full bg-muted-foreground transition-transform peer-checked:translate-x-5 peer-checked:bg-primary-foreground"
        />
      </span>
    </label>
  );
}
