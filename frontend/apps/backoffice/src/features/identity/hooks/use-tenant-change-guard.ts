import { useQueryClient, type Query } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { SESSION_QUERY_KEY } from '../../../lib/query-client';
import { useTenantPin } from '../../../lib/tenant-pin';
import { useSession } from './use-session';

const isSession = (query: Query) =>
  query.queryKey.length === SESSION_QUERY_KEY.length && SESSION_QUERY_KEY.every((part, i) => query.queryKey[i] === part);

/**
 * FE-F16: pins the tab to the company of its first session read. When a later read (a focus reread, or the one after
 * a 409 TENANT_CHANGED) names another company, another tab switched the shared BFF selection: everything loaded is
 * reset, the store is dropped, the tab goes to "/" and says so, and only then does the pin follow. Until then writes
 * still name the old company and the BFF refuses them. Lives above the session gate, so the notice survives the reset.
 */
export function useTenantChangeGuard(): string | undefined {
  const session = useSession();
  const pin = useTenantPin();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [notice, setNotice] = useState<string>();
  const tenantId = session.data?.tenantId;
  const tenantName = session.data?.tenantName;

  useEffect(() => {
    if (!tenantId || pin.get() === tenantId) return;
    if (pin.get() === undefined) {
      pin.set(tenantId);
      return;
    }
    // The session was just read; resetting it too would only read it again.
    const reset = queryClient.resetQueries({ predicate: (query) => !isSession(query) });
    navigate('/');
    void reset.then(() => {
      setNotice(`A empresa ativa mudou noutro separador para ${tenantName ?? 'outra empresa'}.`);
      pin.set(tenantId);
    });
  }, [tenantId, tenantName, pin, queryClient, navigate]);

  return notice;
}
