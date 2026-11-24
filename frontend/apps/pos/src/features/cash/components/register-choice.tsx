import type { RegisterSummary } from '../hooks/use-cash';

type Props = {
  /** The group's name, for radios of two forms on one screen. */
  name: string;
  registers: RegisterSummary[];
  value: string | undefined;
  onChange: (registerId: string) => void;
  disabled?: boolean;
};

/** The store's active registers as large radio tiles (touch: 56 px), one tap to choose; inactive ones are not offered. */
export function RegisterChoice({ name, registers, value, onChange, disabled }: Props) {
  const active = registers.filter((register) => register.active);
  return (
    <fieldset className="flex flex-col gap-2" disabled={disabled}>
      <legend className="mb-2 text-sm font-medium text-muted-foreground">Caixa</legend>
      {active.length === 0 ? (
        <p className="text-muted-foreground">Esta loja não tem caixas ativas. Um gestor cria as caixas no BackOffice.</p>
      ) : (
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          {active.map((register) => (
            <label
              key={register.id}
              className="flex min-h-14 cursor-pointer items-center gap-3 rounded-xl border-2 bg-card px-4 text-base font-semibold has-checked:border-primary has-checked:bg-success-tint has-focus-visible:ring-2 has-focus-visible:ring-ring has-focus-visible:ring-offset-2 has-focus-visible:ring-offset-card"
            >
              <input
                type="radio"
                name={name}
                value={register.id}
                checked={value === register.id}
                onChange={() => register.id && onChange(register.id)}
                className="size-5 accent-primary"
              />
              <span>
                {register.name} <span className="font-normal text-muted-foreground">({register.code})</span>
              </span>
            </label>
          ))}
        </div>
      )}
    </fieldset>
  );
}
