import {
  Button,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '@restaurantpro/ui';
import { Building2, ChevronDown } from 'lucide-react';
import { errorMessage } from '../../../lib/error-messages';
import { useSelectTenant } from '../hooks/use-select-tenant';
import { useSession, useTenants } from '../hooks/use-session';
import { tenantStatusLabel } from './tenant-status';

/**
 * The company in the top bar (stories 4, 5): only its name with one membership, a menu with several. A menu, not a
 * native select, so moving through the options with the keyboard never switches company by accident.
 */
export function TenantSwitcher() {
  const { data: tenants = [] } = useTenants();
  const { data: session } = useSession();
  const select = useSelectTenant();
  // The company is the one the server resolved this request in (SessionView.tenantId, FE-F08), never a client memory:
  // another tab of the same BFF session may have switched it.
  const currentId = session?.tenantId ?? undefined;
  const label = session?.tenantName ?? 'Escolher empresa';

  if (tenants.length < 2) {
    return (
      <span className="flex min-w-0 items-center gap-2 text-sm font-medium">
        <Building2 aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
        <span className="truncate">{label}</span>
      </span>
    );
  }

  return (
    <div className="flex items-center gap-2">
      <DropdownMenu modal={false}>
        <DropdownMenuTrigger asChild>
          <Button
            variant="outline"
            disabled={select.isPending}
            aria-label={`Empresa: ${label}`}
            className="max-w-72"
          >
            <Building2 aria-hidden="true" className="size-5 text-muted-foreground" />
            <span className="truncate">{label}</span>
            <ChevronDown aria-hidden="true" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" className="min-w-64">
          <DropdownMenuLabel>Empresa</DropdownMenuLabel>
          <DropdownMenuRadioGroup
            value={currentId}
            onValueChange={(tenantId) => tenantId !== currentId && select.mutate(tenantId)}
          >
            {tenants.map((tenant) => {
              const status = tenantStatusLabel(tenant.status);
              return (
                <DropdownMenuRadioItem key={tenant.tenantId} value={tenant.tenantId}>
                  {tenant.legalName}
                  {status && <span className="ml-auto text-xs text-muted-foreground"> — {status}</span>}
                </DropdownMenuRadioItem>
              );
            })}
          </DropdownMenuRadioGroup>
        </DropdownMenuContent>
      </DropdownMenu>
      {select.isError && (
        <p role="alert" className="text-sm text-error">
          {errorMessage(select.error)}
        </p>
      )}
    </div>
  );
}
