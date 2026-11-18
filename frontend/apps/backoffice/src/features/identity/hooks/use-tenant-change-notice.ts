import { useEffect, useState } from 'react';
import { forgetTenantChange, tenantChangedBeforeLoad } from '../../../lib/browser';
import { useSession } from './use-session';

/** After a page load caused by a switch in another tab (FE-F16), names the company the page is now in, once. */
export function useTenantChangeNotice(): string | undefined {
  const [changed] = useState(tenantChangedBeforeLoad);
  useEffect(() => {
    if (changed) forgetTenantChange();
  }, [changed]);
  const tenantName = useSession().data?.tenantName;
  return changed && tenantName ? `A empresa ativa mudou noutro separador para ${tenantName}.` : undefined;
}
