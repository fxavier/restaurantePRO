import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ConnectionBanner } from './connection-banner';

describe('ConnectionBanner', () => {
  it('announces that nothing is confirmed while the service is unreachable', () => {
    render(<ConnectionBanner />);

    expect(screen.getByRole('alert')).toHaveTextContent('Sem ligação — nenhuma operação é confirmada');
  });
});
