import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { NativeSelect } from './native-select';

describe('NativeSelect', () => {
  it('is a native select found by its label, passing the choice to its handler', async () => {
    const onChange = vi.fn();
    render(
      <>
        <label htmlFor="kind">Tipo</label>
        <NativeSelect id="kind" defaultValue="A" onChange={(event) => onChange(event.target.value)}>
          <option value="A">Faturação</option>
          <option value="B">Entrega</option>
        </NativeSelect>
      </>,
    );

    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Tipo' }), 'Entrega');

    expect(onChange).toHaveBeenCalledWith('B');
  });

  it("takes Input's field look, and a caller's width over the full one", () => {
    render(
      <NativeSelect aria-label="Loja" aria-invalid className="w-auto max-w-64" disabled>
        <option>Baixa</option>
      </NativeSelect>,
    );
    const select = screen.getByRole('combobox', { name: 'Loja' });
    expect(select).toBeDisabled();
    expect(select).toHaveAttribute('aria-invalid', 'true');
    expect(select).toHaveClass('rounded-xl', 'bg-card', 'w-auto', 'max-w-64');
    expect(select).not.toHaveClass('w-full');
  });
});
