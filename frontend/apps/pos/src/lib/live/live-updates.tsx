import { ApiError } from '@restaurantpro/api-client';
import { type QueryKey, useQueryClient } from '@tanstack/react-query';
import { createContext, type ReactNode, useContext, useEffect, useState } from 'react';
import { useServices } from '../services';
import { type ChangeNotice, startChangeStream, type StreamState } from './change-stream';

/** FC §10: what each topic invalidates. The notice never carries data, and none is ever applied. */
function keysOf({ topic, id }: ChangeNotice): QueryKey[] {
  switch (topic) {
    case 'TABLES':
      return [['pos', 'tables']];
    case 'ORDER':
      return id ? [['pos', 'orders', id], ['pos', 'tables']] : [['pos', 'orders'], ['pos', 'tables']];
    case 'CATALOG':
      return [['pos', 'catalog']];
    case 'PRINT_JOBS':
      return [['pos', 'print-jobs']];
    case 'PAYMENT_ATTEMPT':
      return id ? [['pos', 'payment-attempts', id]] : [['pos', 'payment-attempts']];
    default:
      return [];
  }
}

const StreamStateContext = createContext<StreamState>('connecting');

/**
 * The POS session's one change stream (§9, FC §10), for as long as this stays mounted: from the login until the
 * session ends. A notice invalidates queries; each opening invalidates every POS query; while the stream is down,
 * the visible screen's queries (the active ones) are read again every 15 s.
 */
export function LiveUpdates({ children }: { children: ReactNode }) {
  const { api } = useServices();
  const queryClient = useQueryClient();
  const [state, setState] = useState<StreamState>('connecting');

  useEffect(
    () =>
      startChangeStream({
        async open(signal) {
          let challenged = false;
          // Runs before the client turns the answer into an ApiError, which keeps no headers.
          const challenge = {
            onResponse({ response }: { response: Response }) {
              challenged = response.headers.has('WWW-Authenticate');
              return undefined;
            },
          };
          try {
            const { data } = await api.GET('/api/v1/pos/notifications', {
              parseAs: 'stream',
              signal,
              middleware: [challenge],
            });
            if (!data) throw new Error('No event stream');
            return data;
          } catch (error) {
            // The terminal token refused (Keycloak down while it renewed): it passes, so retry like no answer.
            if (challenged) throw new Error('Terminal token refused', { cause: error });
            throw error;
          }
        },
        onChange: (notice) => {
          for (const queryKey of keysOf(notice)) void queryClient.invalidateQueries({ queryKey });
        },
        onOpen: () => void queryClient.invalidateQueries({ queryKey: ['pos'] }),
        onPoll: () => void queryClient.refetchQueries({ queryKey: ['pos'], type: 'active' }),
        onState: setState,
        // 403: no DINING_VIEW or PRODUCTION_VIEW; a 401 from a handler. POS_SESSION_INVALID ends the session instead.
        isRefusal: (error) =>
          error instanceof ApiError &&
          (error.status === 403 || (error.status === 401 && error.code !== 'POS_SESSION_INVALID')),
      }),
    [api, queryClient],
  );

  return <StreamStateContext value={state}>{children}</StreamStateContext>;
}

/** The change stream's state, for the status bar. */
export function useStreamState(): StreamState {
  return useContext(StreamStateContext);
}
