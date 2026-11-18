import { ConnectionBanner } from '@restaurantpro/ui';
import { useEffect, useSyncExternalStore } from 'react';
import { Navigate, Outlet, useNavigate } from 'react-router';
import { useStoredDevice } from '../lib/auth/use-stored-device';
import { useServices } from '../lib/services';
import { RevokedState } from './revoked-state';

/**
 * The guard of every route but `/enrol` (FC §4): no enrolled device → `/enrol`. With one, it keeps the terminal token
 * (D182): "Sem ligação" while Keycloak is unreachable; a refused token shows the revoked state, key kept.
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

  /** The only path that erases the device key (after the confirm dialog). Stopping last avoids a flash of the PIN screen. */
  async function reEnrol() {
    await devices.clear();
    tokens.stop();
    navigate('/enrol', { replace: true });
  }

  if (device.loading) return null;
  if (!device.record?.enrolment) return <Navigate to="/enrol" replace />;
  if (status === 'revoked') return <RevokedState onReEnrol={() => void reEnrol()} />;
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
