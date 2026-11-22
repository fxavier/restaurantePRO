import { ApiError } from '@restaurantpro/api-client';
import { Button } from '@restaurantpro/ui';
import { isUnavailable } from '../../../lib/errors';
import type { ReadState } from '../hooks/use-choices';

/** Why a picker has no choices when its read failed: a refusal is final, anything else can be read again. */
function reason({ error, what }: ReadState): string {
  if (error instanceof ApiError && error.status === 403) return `Sem permissão para ver ${what}.`;
  return isUnavailable(error) ? `Sem ligação: não foi possível carregar ${what}.` : `Não foi possível carregar ${what}.`;
}

/** A picker's failed read, said where the picker is, instead of an empty list (FC §9). */
export function ChoicesError({ list }: { list: ReadState }) {
  if (!list.error) return null;
  const refused = list.error instanceof ApiError && list.error.status === 403;
  return (
    <div role="alert" className="flex flex-wrap items-center gap-2 text-sm text-error">
      <span>{reason(list)}</span>
      {!refused && (
        <Button type="button" variant="outline" size="sm" onClick={list.retry}>
          Tentar novamente
        </Button>
      )}
    </div>
  );
}
