import { Button, Card, CardContent, CardHeader, CardTitle, Input, Label } from '@restaurantpro/ui';
import { Search, X } from 'lucide-react';
import { useId, useState } from 'react';
import { searchText } from '../search-text';
import { DebtsTable } from './debts-table';
import { SearchResults } from './search-results';

type Query = { kind: 'none' } | { kind: 'tooShort' } | { kind: 'search'; q: string };

/**
 * The left pane: a search by name, phone or tax id; without one, the customers who owe (the search needs at least 3
 * letters, so there is no "all customers" list in the contract).
 */
export function CustomerList({ onSelect }: { onSelect: (customerId: string) => void }) {
  const inputId = useId();
  const hintId = useId();
  const [text, setText] = useState('');
  const [query, setQuery] = useState<Query>({ kind: 'none' });

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    const q = searchText(text);
    setQuery(q === undefined ? { kind: 'tooShort' } : { kind: 'search', q });
  };
  const clear = () => {
    setText('');
    setQuery({ kind: 'none' });
  };

  return (
    <Card className="gap-4">
      <CardHeader>
        <form role="search" onSubmit={submit} className="flex flex-col gap-2">
          <Label htmlFor={inputId}>Pesquisar cliente</Label>
          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                id={inputId}
                type="search"
                value={text}
                onChange={(event) => setText(event.target.value)}
                aria-describedby={hintId}
                placeholder="Nome, telefone ou NIF"
                autoComplete="off"
                className="pl-9"
              />
            </div>
            <Button type="submit" variant="outline">
              Pesquisar
            </Button>
          </div>
          <p id={hintId} className={query.kind === 'tooShort' ? 'text-sm text-error' : 'text-sm text-muted-foreground'}>
            {query.kind === 'tooShort' ? 'Escreva pelo menos 3 letras ou 4 algarismos.' : 'Pelo menos 3 letras ou 4 algarismos.'}
          </p>
        </form>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {query.kind === 'search' ? (
          <>
            <div className="flex items-center justify-between gap-2">
              <CardTitle className="text-base">Resultados para “{query.q}”</CardTitle>
              <Button variant="ghost" size="sm" onClick={clear}>
                <X aria-hidden="true" />
                Limpar pesquisa
              </Button>
            </div>
            <SearchResults q={query.q} onSelect={onSelect} />
          </>
        ) : (
          <>
            <CardTitle className="text-base">Com saldo devedor</CardTitle>
            <DebtsTable onSelect={onSelect} />
          </>
        )}
      </CardContent>
    </Card>
  );
}
