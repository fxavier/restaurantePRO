import { screen } from '@testing-library/react';
import { delay, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { atCash, json, OPEN_SESSION } from '../../test/cash';
import { problem, SESSION } from '../../test/server';

describe('the cash screen states', () => {
  it('says it is loading while the session is read', async () => {
    await atCash({
      session: [
        async () => {
          await delay('infinite');
          return HttpResponse.json(OPEN_SESSION);
        },
      ],
    });

    expect(await screen.findByRole('status', { name: 'A carregar a caixa…' })).toBeInTheDocument();
  });

  it('says there is no connection when the session cannot be read, and reads it again on "Tentar novamente"', async () => {
    // A query retries a network error twice on its own (FC §5): three failures before the screen says so.
    const offline = () => HttpResponse.error();
    const { user, sessions } = await atCash({ session: [offline, offline, offline, json(OPEN_SESSION)] });

    expect(await screen.findByRole('alert')).toHaveTextContent('Sem ligação — não foi possível carregar a caixa.');
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));

    expect(await screen.findByRole('region', { name: /^Caixa Principal/ })).toBeInTheDocument();
    expect(sessions.length).toBeGreaterThan(1);
  });

  it("shows the server's error when it refuses to read the session", async () => {
    await atCash({ session: [() => problem(500, 'INTERNAL')] });

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar a caixa. Erro inesperado (INTERNAL).');
  });

  it('without the cash permission says so and reads nothing', async () => {
    const { sessions } = await atCash({ permissions: SESSION.permissions });

    expect(await screen.findByText('Sem permissão para operar a caixa.')).toBeInTheDocument();
    expect(sessions).toHaveLength(0);
  });
});
