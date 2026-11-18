import { useSyncExternalStore } from 'react';
import { useServices } from '../services';
import type { StreamState } from './change-stream';
import { useStreamState } from './live-updates';

/**
 * The POS's connection as the status bar shows it: the change stream's state, or `down` while Keycloak cannot give
 * the terminal a token (FC §11).
 */
export function useConnection(): StreamState {
  const { tokens } = useServices();
  const tokenStatus = useSyncExternalStore(tokens.subscribe, tokens.status);
  const stream = useStreamState();
  return tokenStatus === 'unavailable' ? 'down' : stream;
}

/** "Sem ligação": every confirming command is disabled meanwhile (FC §11). */
export function useCommandsBlocked(): boolean {
  return useConnection() === 'down';
}
