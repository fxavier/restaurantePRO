import { Button } from '@restaurantpro/ui';
import { errorMessage } from '../../../lib/error-messages';
import { useSelectTenant } from '../hooks/use-select-tenant';
import type { TenantChoice } from '../types';
import { tenantStatusLabel } from './tenant-status';

/** The user's companies as buttons; choosing one selects it in the BFF session and reloads everything. */
export function TenantChooser({ tenants }: { tenants: TenantChoice[] }) {
  const select = useSelectTenant();
  return (
    <div className="flex w-full flex-col gap-2">
      <ul className="flex flex-col gap-2">
        {tenants.map((tenant) => {
          const status = tenantStatusLabel(tenant.status);
          return (
            <li key={tenant.tenantId}>
              <Button
                variant="outline"
                className="h-auto w-full justify-between py-2"
                disabled={select.isPending}
                onClick={() => tenant.tenantId && select.mutate(tenant.tenantId)}
              >
                <span className="flex flex-col items-start">
                  <span>{tenant.legalName}</span>
                  <span className="text-xs text-muted-foreground">{tenant.code}</span>
                </span>
                {status && <span className="text-xs text-muted-foreground">{status}</span>}
              </Button>
            </li>
          );
        })}
      </ul>
      {select.isError && (
        <p role="alert" className="text-sm text-error">
          {errorMessage(select.error)}
        </p>
      )}
    </div>
  );
}
