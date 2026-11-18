import { readEventStream } from '@restaurantpro/api-client';

/**
 * `connecting` until the first answer; `open` while a stream is read; `down` between a loss and the next opening;
 * `refused` once the backend refused the stream (401, 403): no more attempts, polling only.
 */
export type StreamState = 'connecting' | 'open' | 'down' | 'refused';

/** A change notice (D60): what changed, never the data. */
export type ChangeNotice = { topic: string; id?: string };

/** FC §10. */
export const BACKOFF_MS = [1_000, 2_000, 5_000, 10_000, 15_000];
export const POLL_MS = 15_000;
/** The backend writes a heartbeat every 25 s; two missed mean the connection is gone, though no error says so. */
export const SILENCE_MS = 60_000;

type Options = {
  /** Opens `GET /api/v1/pos/notifications` and resolves with its body once the answer starts. */
  open(signal: AbortSignal): Promise<ReadableStream<Uint8Array>>;
  onChange(notice: ChangeNotice): void;
  /** Every opening, the first too: what changed while no stream was read is read again. */
  onOpen(): void;
  /** Every POLL_MS while the stream is down. */
  onPoll(): void;
  onState(state: StreamState): void;
  /** True for a failure retrying cannot fix (the employee may not read the stream). */
  isRefusal(error: unknown): boolean;
};

/**
 * Keeps one change stream open until stopped (FC §10): reconnects with backoff after a failure, an end or a silence,
 * and polls while down. Returns `stop`, after which nothing is called any more.
 */
export function startChangeStream({ open, onChange, onOpen, onPoll, onState, isRefusal }: Options): () => void {
  let stopped = false;
  let failures = 0;
  let current: AbortController | undefined;
  let retry: ReturnType<typeof setTimeout> | undefined;
  let poll: ReturnType<typeof setInterval> | undefined;
  let state: StreamState = 'connecting';

  function setState(next: StreamState) {
    if (stopped || next === state) return;
    state = next;
    clearInterval(poll);
    if (next === 'down' || next === 'refused') poll = setInterval(onPoll, POLL_MS);
    onState(next);
  }

  async function connect() {
    const attempt = new AbortController();
    current = attempt;
    // A silent stream is given up by cancelling its read, which closes the connection; `stop` aborts the request.
    const reading = new AbortController();
    const read = AbortSignal.any([attempt.signal, reading.signal]);
    let silence: ReturnType<typeof setTimeout> | undefined;
    const alive = () => {
      clearTimeout(silence);
      silence = setTimeout(() => reading.abort(), SILENCE_MS);
    };
    try {
      const body = await open(attempt.signal);
      if (stopped) return;
      failures = 0;
      setState('open');
      onOpen();
      alive();
      await readEventStream(
        body,
        {
          onMessage: (message) => {
            alive();
            const notice = message.event === 'change' ? parse(message.data) : undefined;
            if (notice) onChange(notice);
          },
          onComment: alive,
        },
        read,
      );
    } catch (error) {
      // No answer, a 5xx, or a read that broke: down, and retried. A refusal is final for this session.
      if (!stopped && isRefusal(error)) {
        setState('refused');
        return;
      }
    } finally {
      clearTimeout(silence);
    }
    if (stopped) return;
    setState('down');
    retry = setTimeout(() => void connect(), BACKOFF_MS[Math.min(failures, BACKOFF_MS.length - 1)]);
    failures += 1;
  }

  void connect();
  return () => {
    stopped = true;
    clearTimeout(retry);
    clearInterval(poll);
    current?.abort();
  };
}

function parse(data: string): ChangeNotice | undefined {
  try {
    const value: unknown = JSON.parse(data);
    if (typeof value !== 'object' || value === null) return undefined;
    const { topic, id } = value as Record<string, unknown>;
    if (typeof topic !== 'string') return undefined;
    return typeof id === 'string' ? { topic, id } : { topic };
  } catch {
    return undefined;
  }
}
