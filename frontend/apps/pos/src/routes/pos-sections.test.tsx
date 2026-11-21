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

// The section bar and the routes only: each section's page owns its heading and content (its feature tests check them).
describe('POS sections', () => {
  it('marks the current section in the section bar, and only that one', async () => {
    const { router } = await at('/cash');

    const sections = await screen.findByRole('navigation', { name: 'Secções' });
    expect(router.state.location.pathname).toBe('/cash');
    expect(within(sections).getByRole('link', { name: 'Caixa & Turno' })).toHaveAttribute('aria-current', 'page');
    expect(within(sections).getByRole('link', { name: 'Sala & Mesas' })).not.toHaveAttribute('aria-current');
  });

  it.each([
    ['/delivery', 'Takeaway & Delivery'],
    ['/reservations', 'Reservas'],
  ])('%s is a section route of its own', async (path, name) => {
    const { router } = await at(path);

    const sections = await screen.findByRole('navigation', { name: 'Secções' });
    expect(router.state.location.pathname).toBe(path);
    expect(within(sections).getByRole('link', { name })).toHaveAttribute('aria-current', 'page');
  });

  it("opens an order's payment page under the section bar", async () => {
    const path = '/orders/6f1c2a9e-0000-4000-8000-000000000500/payment';
    const { router } = await at(path);

    expect(await screen.findByRole('navigation', { name: 'Secções' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(path);
  });

  it('goes from one section to another, and shows the section bar on the table map too', async () => {
    const { user, router } = await at('/cash');

    await user.click(await screen.findByRole('link', { name: 'Sala & Mesas' }));

    expect(router.state.location.pathname).toBe('/tables');
    const sections = screen.getByRole('navigation', { name: 'Secções' });
    expect(within(sections).getByRole('link', { name: 'Sala & Mesas' })).toHaveAttribute('aria-current', 'page');
  });
});
