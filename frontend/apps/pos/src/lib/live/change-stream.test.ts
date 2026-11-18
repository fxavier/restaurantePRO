import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { startChangeStream, type StreamState } from './change-stream';

/** A body the test feeds and ends; `cancelled` once the reader gave it up. */
function body() {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const state = { cancelled: false };
  const stream = new ReadableStream<Uint8Array>({
    start: (c) => void (controller = c),
    cancel: () => void (state.cancelled = true),
  });
  return {
    stream,
    state,
    write: (text: string) => controller.enqueue(new TextEncoder().encode(text)),
    end: () => controller.close(),
  };
}

/** The stream over `answers` (`'fail'` rejects, a body opens); the last answer repeats. Pure fake time throughout. */
function run(...answers: ('fail' | ReturnType<typeof body>)[]) {
  const states: StreamState[] = [];
  const open = vi.fn(async () => {
    const answer = answers.length > 1 ? answers.shift()! : answers[0]!;
    if (answer === 'fail') throw new TypeError('network');
    return answer.stream;
  });
  const onPoll = vi.fn();
  const onOpen = vi.fn();
  const onChange = vi.fn();
  const stop = startChangeStream({
    open,
    onChange,
    onOpen,
    onPoll,
    onState: (state) => states.push(state),
    isRefusal: () => false,
  });
  return { open, onPoll, onOpen, onChange, states, stop };
}

const advance = (ms: number) => vi.advanceTimersByTimeAsync(ms);

describe('startChangeStream timing', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('reconnects after 1, 2, 5, 10 and 15 s, then every 15 s', async () => {
    const { open, stop } = run('fail');
    await advance(0);
    expect(open).toHaveBeenCalledTimes(1);

    const steps: [wait: number, attempts: number][] = [
      [1_000, 2],
      [2_000, 3],
      [5_000, 4],
      [10_000, 5],
      [15_000, 6],
      [15_000, 7],
    ];
    for (const [wait, attempts] of steps) {
      await advance(wait - 1);
      expect(open).toHaveBeenCalledTimes(attempts - 1);
      await advance(1);
      expect(open).toHaveBeenCalledTimes(attempts);
    }
    stop();
  });

  it('polls every 15 s while down, and stops polling once a stream opens', async () => {
    const opened = body();
    // Attempts at 0, 1, 3, 8 s fail; the one at 18 s opens.
    const { onPoll, onOpen, states, stop } = run('fail', 'fail', 'fail', 'fail', opened);
    await advance(0);
    expect(states).toEqual(['down']);

    await advance(14_999);
    expect(onPoll).toHaveBeenCalledTimes(0);
    await advance(1);
    expect(onPoll).toHaveBeenCalledTimes(1);
    await advance(3_000);
    expect(states).toEqual(['down', 'open']);
    expect(onOpen).toHaveBeenCalledTimes(1);

    await advance(60_000 - 1);
    expect(onPoll).toHaveBeenCalledTimes(1);
    stop();
  });

  it('a backoff starts again from 1 s after a stream opened', async () => {
    const first = body();
    const { open, stop } = run(first, 'fail');
    await advance(0);

    first.end();
    await advance(999);
    expect(open).toHaveBeenCalledTimes(1);
    await advance(1);
    expect(open).toHaveBeenCalledTimes(2);
    await advance(1_999);
    expect(open).toHaveBeenCalledTimes(2);
    await advance(1);
    expect(open).toHaveBeenCalledTimes(3);
    stop();
  });

  it('60 s without a byte counts as down; a heartbeat starts the 60 s again', async () => {
    const quiet = body();
    const { states, stop } = run(quiet, 'fail');
    await advance(0);
    expect(states).toEqual(['open']);

    await advance(25_000);
    quiet.write(':heartbeat\n\n');
    await advance(60_000 - 1);
    expect(states).toEqual(['open']);
    await advance(1);
    expect(states).toEqual(['open', 'down']);
    expect(quiet.state.cancelled).toBe(true);
    stop();
  });

  it('after stop nothing is opened, polled or reported', async () => {
    const { open, onPoll, states, stop } = run('fail');
    await advance(0);
    stop();

    await advance(120_000);
    expect(open).toHaveBeenCalledTimes(1);
    expect(onPoll).not.toHaveBeenCalled();
    expect(states).toEqual(['down']);
  });
});
