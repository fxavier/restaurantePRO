import { Button, Input, Label } from '@restaurantpro/ui';
import { Search } from 'lucide-react';
import { useId, useState, type ComponentProps, type ReactNode } from 'react';
import { withField } from '../filters';
import type { OrderFilter } from '../hooks/use-orders';
import { lifecycles, modes } from '../labels';

type OrderFiltersProps = {
  filter: OrderFilter;
  onChange: (filter: OrderFilter) => void;
  error: string | undefined;
};

const lifecycleOptions = Object.entries(lifecycles).map(([value, { label }]) => ({ value, label }));
const modeOptions = Object.entries(modes).map(([value, label]) => ({ value, label }));

/**
 * The search's period (business days, inclusive), state and mode, which apply at once, and the order number or table
 * code, which applies on "Pesquisar" (not on every key).
 */
export function OrderFilters({ filter, onChange, error }: OrderFiltersProps) {
  const id = useId();
  const [text, setText] = useState(filter.q ?? '');
  const set = (key: keyof OrderFilter) => (value: string) => onChange(withField(filter, key, value));

  return (
    <form
      role="search"
      aria-label="Pesquisar pedidos"
      className="flex flex-col gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        set('q')(text);
      }}
    >
      <div className="flex flex-wrap items-end gap-4">
        <Field id={`${id}-from`} label="De">
          <Input id={`${id}-from`} type="date" value={filter.from ?? ''} aria-invalid={!!error} onChange={(event) => set('from')(event.target.value)} />
        </Field>
        <Field id={`${id}-to`} label="Até">
          <Input id={`${id}-to`} type="date" value={filter.to ?? ''} aria-invalid={!!error} onChange={(event) => set('to')(event.target.value)} />
        </Field>
        <Field id={`${id}-status`} label="Estado">
          <Choice id={`${id}-status`} value={filter.status} all="Todos os estados" options={lifecycleOptions} onChange={set('status')} />
        </Field>
        <Field id={`${id}-mode`} label="Modo">
          <Choice id={`${id}-mode`} value={filter.mode} all="Todos os modos" options={modeOptions} onChange={set('mode')} />
        </Field>
        <Field id={`${id}-q`} label="Número ou mesa">
          <div className="flex gap-2">
            <Input id={`${id}-q`} type="search" value={text} autoComplete="off" placeholder="1043 ou M12" onChange={(event) => setText(event.target.value)} />
            <Button type="submit" variant="outline">
              <Search aria-hidden="true" />
              Pesquisar
            </Button>
          </div>
        </Field>
      </div>
      {error && (
        <p role="alert" className="text-sm text-error">
          {error}
        </p>
      )}
    </form>
  );
}

function Field({ id, label, children }: { id: string; label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id}>{label}</Label>
      {children}
    </div>
  );
}

type ChoiceProps = {
  value: string | undefined;
  all: string;
  options: { value: string; label: string }[];
  onChange: (value: string) => void;
} & Omit<ComponentProps<'select'>, 'value' | 'onChange'>;

function Choice({ value, all, options, onChange, ...props }: ChoiceProps) {
  return (
    <select
      {...props}
      value={value ?? ''}
      onChange={(event) => onChange(event.target.value)}
      className="h-9 min-w-40 rounded-md border border-input bg-muted px-3 text-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
    >
      <option value="">{all}</option>
      {options.map((option) => (
        <option key={option.value} value={option.value}>
          {option.label}
        </option>
      ))}
    </select>
  );
}
