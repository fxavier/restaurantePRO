import { describe, expect, it } from 'vitest';
import { createPosSession } from './pos-session';

const view = (name: string, posSession?: string, mustChangePin = false) => ({
  employeeId: `id-${name}`,
  storeId: 'store-1',
  name,
  mustChangePin,
  permissions: [],
  posSession,
});

describe('POS session', () => {
  it('applies a PIN-change answer to the session that sent it, keeping the token', () => {
    const session = createPosSession();
    session.start(view('Ana', 'token-ana', true));

    session.update(view('Ana'), 'token-ana');

    expect(session.employee()).toMatchObject({ name: 'Ana', mustChangePin: false });
    expect(session.token()).toBe('token-ana');
  });

  it('ignores a late answer for a session that ended, even when another one started', () => {
    const session = createPosSession();
    session.start(view('Ana', 'token-ana', true));
    session.end('idle');
    session.start(view('Bruno', 'token-bruno', true));

    session.update(view('Ana'), 'token-ana');

    expect(session.employee()).toMatchObject({ name: 'Bruno', mustChangePin: true });
  });
});
