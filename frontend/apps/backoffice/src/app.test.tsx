import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';
import { routes } from './routes';

describe('BackOffice', () => {
  it('boots to the placeholder screen in Portuguese', () => {
    render(<RouterProvider router={createMemoryRouter(routes)} />);

    expect(screen.getByRole('heading', { level: 1, name: 'restaurantPRO — BackOffice' })).toBeInTheDocument();
    expect(screen.getByText('A gestão da empresa chega em breve.')).toBeInTheDocument();
  });
});
