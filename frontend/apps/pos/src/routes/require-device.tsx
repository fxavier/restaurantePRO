import { ConnectionBanner } from '@restaurantpro/ui';
import { useEffect, useSyncExternalStore } from 'react';
import { Navigate, Outlet, useNavigate } from 'react-router';
import { useStoredDevice } from '../lib/auth/use-stored-device';
import { useServices } from '../lib/services';

/**
 * The guard of every route but `/enrol` (FC §4): no enrolled device → `/enrol`. With one, it keeps the terminal token
 * (D182): "Sem ligação" while Keycloak is unreachable, and back to `/enrol`, key forgotten, when the token is refused.
 */
export function RequireDevice() {
  const { tokens, devices } = useServices();
  const device = useStoredDevice();
  const status = useSyncExternalStore(tokens.subscribe, tokens.status);
  const navigate = useNavigate();
  const enrolled = !device.loading ? device.record : undefined;

  useEffect(() => {
    if (enrolled?.enrolment && !tokens.active()) void tokens.start(enrolled.enrolment, enrolled.keyPair.privateKey);
  }, [enrolled, tokens]);

  useEffect(() => {
    if (status !== 'revoked') return;
    tokens.stop();
    void devices.clear().finally(() => navigate('/enrol', { replace: true, state: { revoked: true } }));
  }, [status, tokens, devices, navigate]);

  if (device.loading) return null;
  if (!device.record?.enrolment) return <Navigate to="/enrol" replace />;
  return (
    <>
      {status === 'unavailable' && (
        // Fixed over the top edge, so its arrival moves nothing on the screen (DS: no layout shift).
        <div className="fixed inset-x-0 top-0 z-50">
          <ConnectionBanner />
        </div>
      )}
      <Outlet />
    </>
  );
}
