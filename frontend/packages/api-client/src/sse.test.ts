import { describe, expect, it } from 'vitest';
import { readEventStream, type SseMessage } from './index';

/** A body whose chunks arrive one read at a time, split exactly where the test says. */
function streamOf(...chunks: (string | Uint8Array)[]) {
  const encoder = new TextEncoder();
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(typeof chunk === 'string' ? encoder.encode(chunk) : chunk);
      controller.close();
    },
  });
}

async function read(...chunks: (string | Uint8Array)[]) {
  const messages: SseMessage[] = [];
  const comments: string[] = [];
  const retries: number[] = [];
  await readEventStream(streamOf(...chunks), {
    onMessage: (message) => messages.push(message),
    onComment: (comment) => comments.push(comment),
    onRetry: (ms) => retries.push(ms),
  });
  return { messages, comments, retries };
}

describe('readEventStream', () => {
  it('dispatches a named event with its data', async () => {
    const { messages } = await read('event: change\ndata: {"topic":"TABLES","id":"t1"}\n\n');

    expect(messages).toEqual([{ event: 'change', data: '{"topic":"TABLES","id":"t1"}' }]);
  });

  it('defaults the event name to "message" and keeps the last id', async () => {
    const { messages } = await read('id: 42\ndata: hi\n\n');

    expect(messages).toEqual([{ event: 'message', data: 'hi', id: '42' }]);
  });

  it('reports comments (the server heartbeat) without dispatching them', async () => {
    const { messages, comments } = await read(':heartbeat\n\n', 'event: change\ndata: x\n\n');

    expect(comments).toEqual(['heartbeat']);
    expect(messages).toEqual([{ event: 'change', data: 'x' }]);
  });

  it('joins an event split across reads, even inside a field name and a multi-byte character', async () => {
    const cedilla = new TextEncoder().encode('ç\n\n');
    const { messages } = await read('ev', 'ent: cha', 'nge\nda', 'ta: a', cedilla.slice(0, 1), cedilla.slice(1));

    expect(messages).toEqual([{ event: 'change', data: 'aç' }]);
  });

  it('accepts CRLF and CR line endings, also when CR and LF arrive in different reads', async () => {
    const { messages } = await read('event: change\r', '\ndata: a\r\n\r\n', 'data: b\r\r');

    expect(messages).toEqual([
      { event: 'change', data: 'a' },
      { event: 'message', data: 'b' },
    ]);
  });

  it('joins multi-line data with newlines', async () => {
    const { messages } = await read('data: one\ndata: two\ndata:three\n\n');

    expect(messages).toEqual([{ event: 'message', data: 'one\ntwo\nthree' }]);
  });

  it('ignores an event without data and unknown fields', async () => {
    const { messages } = await read('event: change\n\n', 'foo: bar\ndata: y\n\n');

    expect(messages).toEqual([{ event: 'message', data: 'y' }]);
  });

  it('reports a retry interval sent by the server', async () => {
    const { retries } = await read('retry: 3000\n\nretry: soon\n\n');

    expect(retries).toEqual([3000]);
  });

  it('resolves when the server ends the stream, dropping an unfinished event: the signal to reconnect', async () => {
    const { messages } = await read('data: done\n\n', 'data: cut off');

    expect(messages).toEqual([{ event: 'message', data: 'done' }]);
  });

  it('rejects when reading fails, which is also a signal to reconnect', async () => {
    const failing = new ReadableStream<Uint8Array>({
      pull(controller) {
        controller.error(new TypeError('network error'));
      },
    });

    await expect(readEventStream(failing, { onMessage: () => {} })).rejects.toThrow('network error');
  });

  it('stops reading when the caller aborts', async () => {
    const controller = new AbortController();
    const neverEnding = new ReadableStream<Uint8Array>({ pull: () => new Promise(() => {}) });

    const reading = readEventStream(neverEnding, { onMessage: () => {} }, controller.signal);
    controller.abort();

    await expect(reading).resolves.toBeUndefined();
  });
});
