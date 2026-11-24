import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { Tabs, TabsContent, TabsList, TabsTrigger } from './tabs';

function PillTabs() {
  return (
    <Tabs defaultValue="a">
      <TabsList variant="pill" aria-label="Áreas">
        <TabsTrigger value="a">Artigos</TabsTrigger>
        <TabsTrigger value="b">Famílias</TabsTrigger>
      </TabsList>
      <TabsContent value="a">Lista de artigos</TabsContent>
      <TabsContent value="b">Lista de famílias</TabsContent>
    </Tabs>
  );
}

describe('Tabs, pill variant', () => {
  it('keeps the tabs behaviour: one selected, another chosen by a tap', async () => {
    render(<PillTabs />);
    expect(screen.getByRole('tablist', { name: 'Áreas' })).toHaveAttribute('data-variant', 'pill');
    expect(screen.getByRole('tab', { name: 'Artigos' })).toHaveAttribute('aria-selected', 'true');

    await userEvent.click(screen.getByRole('tab', { name: 'Famílias' }));

    expect(screen.getByRole('tab', { name: 'Famílias' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tabpanel', { name: 'Famílias' })).toHaveTextContent('Lista de famílias');
  });
});
