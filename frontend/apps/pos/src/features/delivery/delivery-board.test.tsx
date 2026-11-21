import { screen, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { atDelivery, DELIVERY, deliveriesEndpoint } from '../../test/delivery';
import { problem } from '../../test/server';

const column = (name: string) => screen.findByRole('region', { name: new RegExp(`^${name}`) });

describe('the dispatch board', () => {
  it('shows the active deliveries by status, each with its order, zone, address and promised time in the store zone', async () => {
    deliveriesEndpoint(() => HttpResponse.json([DELIVERY.pending, DELIVERY.out, DELIVERY.returned]));
    await atDelivery();

    const pending = await column('Por expedir');
    const card = within(pending).getByRole('article', { name: 'Entrega n.º 82' });
    expect(card).toHaveTextContent('Z1');
    expect(card).toHaveTextContent('Av. Julius Nyerere 1200, 3.º andar, Maputo');
    expect(card).toHaveTextContent('841234567');
    expect(card).toHaveTextContent('Prometido às 20:15');
    expect(within(await column('Em entrega')).getByRole('article', { name: 'Entrega n.º 85' })).toBeInTheDocument();
    const returned = within(await column('Devolvidas')).getByRole('article', { name: 'Entrega n.º 79' });
    expect(returned).toHaveTextContent('Cliente ausente');
  });

  it('says so while it loads, then when there is no delivery under way', async () => {
    let answer!: () => void;
    const answered = new Promise<void>((resolve) => (answer = resolve));
    deliveriesEndpoint(async () => {
      await answered;
      return HttpResponse.json([]);
    });
    await atDelivery();

    expect(await screen.findByRole('status', { name: 'A carregar as entregas…' })).toBeInTheDocument();
    answer();
    expect(await screen.findByText('Nenhuma entrega em curso.')).toBeInTheDocument();
  });

  it('a refusal says what failed and reads the board again on "Tentar novamente"', async () => {
    const reads = deliveriesEndpoint(
      () => problem(500, 'INTERNAL'),
      () => HttpResponse.json([DELIVERY.pending]),
    );
    const { user } = await atDelivery();

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar as entregas. Erro inesperado (INTERNAL).');
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));

    expect(await screen.findByRole('article', { name: 'Entrega n.º 82' })).toBeInTheDocument();
    expect(reads).toHaveLength(2);
  });

  it('without a connection it says the board could not be read', async () => {
    deliveriesEndpoint(() => HttpResponse.error());
    await atDelivery();

    expect(await screen.findByRole('alert')).toHaveTextContent('Sem ligação — não foi possível carregar as entregas.');
  });
});
