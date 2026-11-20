import { act, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderPos } from '../test/render-pos';
import { token, tokenEndpoint } from '../test/server';

/** Logged in (which lands on the tables, as a PIN login does), then at `path`. */
async function at(path: string) {
  tokenEndpoint(token('jwt-1'));
  const rendered = await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables' });
  await act(() => rendered.router.navigate(path));
  return rendered;
}

describe('POS sections', () => {
  it('names the current section in the section bar, and the page says it is being prepared', async () => {
    await at('/cash');

    expect(await screen.findByRole('heading', { name: 'Caixa & Turno' })).toBeInTheDocument();
    expect(screen.getByText('Em preparação.')).toBeInTheDocument();
    const sections = screen.getByRole('navigation', { name: 'Secções' });
    expect(within(sections).getByRole('link', { name: 'Caixa & Turno' })).toHaveAttribute('aria-current', 'page');
    expect(within(sections).getByRole('link', { name: 'Sala & Mesas' })).not.toHaveAttribute('aria-current');
  });

  it.each([
    ['/delivery', 'Takeaway & Delivery'],
    ['/reservations', 'Reservas'],
  ])('%s is its own section page', async (path, name) => {
    await at(path);

    expect(await screen.findByRole('heading', { name })).toBeInTheDocument();
    const sections = screen.getByRole('navigation', { name: 'Secções' });
    expect(within(sections).getByRole('link', { name })).toHaveAttribute('aria-current', 'page');
  });

  it("opens an order's payment page under the section bar", async () => {
    await at('/orders/6f1c2a9e-0000-4000-8000-000000000500/payment');

    expect(await screen.findByRole('heading', { name: 'Conta & Pagamento' })).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Secções' })).toBeInTheDocument();
  });

  it('goes from one section to another, and shows the section bar on the table map too', async () => {
    const { user, router } = await at('/cash');

    await user.click(await screen.findByRole('link', { name: 'Sala & Mesas' }));

    expect(router.state.location.pathname).toBe('/tables');
    const sections = screen.getByRole('navigation', { name: 'Secções' });
    expect(within(sections).getByRole('link', { name: 'Sala & Mesas' })).toHaveAttribute('aria-current', 'page');
  });
});
