/** One dispatched server-sent event. `id` is the last event id seen on the stream, when the server sent one. */
export type SseMessage = { event: string; data: string; id?: string };

export type SseHandlers = {
  onMessage: (message: SseMessage) => void;
  /** A comment line, such as the backend's heartbeat (`:heartbeat`); proof the stream is alive. */
  onComment?: (comment: string) => void;
  /** The server's `retry:` advice, in milliseconds. */
  onRetry?: (milliseconds: number) => void;
};

/**
 * Reads a `text/event-stream` body (HTML Living Standard, "event stream interpretation"). `EventSource` cannot send
 * `Authorization` or `X-Pos-Session`, so the POS reads the stream from `fetch` (FC §10). The promise resolves when the
 * server ends the stream or the signal aborts, and rejects when reading fails: either way the caller reconnects.
 */
export async function readEventStream(
  body: ReadableStream<Uint8Array>,
  handlers: SseHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  const onAbort = () => void reader.cancel().catch(() => {});
  signal?.addEventListener('abort', onAbort, { once: true });
  const parser = new SseParser(handlers);
  try {
    if (signal?.aborted) {
      onAbort();
      return;
    }
    for (;;) {
      const { done, value } = await reader.read();
      if (done || signal?.aborted) return;
      parser.push(decoder.decode(value, { stream: true }));
    }
  } finally {
    signal?.removeEventListener('abort', onAbort);
  }
}

class SseParser {
  private buffer = '';
  private afterCarriageReturn = false;
  private started = false;
  private data: string[] = [];
  private event = '';
  private lastId: string | undefined;

  constructor(private readonly handlers: SseHandlers) {}

  push(text: string) {
    // An empty read carries no characters; it must not forget a CR still waiting for its LF.
    if (text === '') return;
    if (!this.started) {
      this.started = true;
      if (text.startsWith('﻿')) text = text.slice(1);
    }
    if (this.afterCarriageReturn && text.startsWith('\n')) text = text.slice(1);
    this.afterCarriageReturn = false;
    this.buffer += text;

    let lineEnd: number;
    while ((lineEnd = this.buffer.search(/[\r\n]/)) >= 0) {
      const line = this.buffer.slice(0, lineEnd);
      let next = lineEnd + 1;
      if (this.buffer[lineEnd] === '\r') {
        if (next === this.buffer.length) this.afterCarriageReturn = true;
        else if (this.buffer[next] === '\n') next += 1;
      }
      this.buffer = this.buffer.slice(next);
      this.line(line);
    }
  }

  private line(line: string) {
    if (line === '') return this.dispatch();
    if (line.startsWith(':')) return this.handlers.onComment?.(line.slice(1).trimStart());

    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);

    switch (field) {
      case 'data':
        this.data.push(value);
        break;
      case 'event':
        this.event = value;
        break;
      case 'id':
        if (!value.includes('\0')) this.lastId = value;
        break;
      case 'retry':
        if (/^\d+$/.test(value)) this.handlers.onRetry?.(Number(value));
        break;
    }
  }

  private dispatch() {
    const data = this.data;
    const event = this.event || 'message';
    this.data = [];
    this.event = '';
    if (data.length === 0) return;
    const message: SseMessage = { event, data: data.join('\n') };
    if (this.lastId !== undefined) message.id = this.lastId;
    this.handlers.onMessage(message);
  }
}
