import { Input, Label, NativeSelect } from '@restaurantpro/ui';
import { useId } from 'react';
import type { DeliveryFilter } from '../hooks/use-deliveries';
import type { EmployeeSummary } from '../types';

type ReportFiltersProps = {
  filter: DeliveryFilter;
  onChange: (filter: DeliveryFilter) => void;
  /** The store's employees; without them (no permission) there is no courier filter. */
  couriers: EmployeeSummary[] | undefined;
  error: string | undefined;
};

/** The report's period (business days, inclusive) and courier. */
export function ReportFilters({ filter, onChange, couriers, error }: ReportFiltersProps) {
  const id = useId();
  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-end gap-4">
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-from`}>De</Label>
          <Input
            id={`${id}-from`}
            type="date"
            value={filter.from}
            aria-invalid={!!error}
            onChange={(event) => onChange({ ...filter, from: event.target.value })}
          />
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-to`}>Até</Label>
          <Input
            id={`${id}-to`}
            type="date"
            value={filter.to}
            aria-invalid={!!error}
            onChange={(event) => onChange({ ...filter, to: event.target.value })}
          />
        </div>
        {couriers && (
          <div className="flex min-w-48 flex-col gap-2">
            <Label htmlFor={`${id}-courier`}>Estafeta</Label>
            <NativeSelect
              id={`${id}-courier`}
              value={filter.courierId ?? ''}
              onChange={(event) => onChange({ ...filter, courierId: event.target.value || undefined })}
            >
              <option value="">Todos os estafetas</option>
              {couriers.map((courier) => (
                <option key={courier.id} value={courier.id}>
                  {courier.name}
                </option>
              ))}
            </NativeSelect>
          </div>
        )}
      </div>
      {error && (
        <p role="alert" className="text-sm text-error">
          {error}
        </p>
      )}
    </div>
  );
}
