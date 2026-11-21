import {
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  ConfirmButton,
  Input,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { ClipboardCheck, ClipboardList, Play, Send } from 'lucide-react';
import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { toDecimal } from '../decimal';
import { formatQuantity } from '../format';
import { useCountAction, useCreateCount, useRecordCountLine } from '../hooks/use-inventory-commands';
import { useCount, useStockItems, useWarehouses } from '../hooks/use-inventory-reads';
import { usePermission } from '../hooks/use-permission';
import { useRowCommand } from '../hooks/use-row-command';
import { nextIntent, type Intent } from '../intent';
import type { CountRequest, CountView } from '../types';
import { CommandError } from './command-error';
import { SELECT } from './command-form';
import { QueryState } from './query-state';
import { countStates, StateBadge } from './state-badge';

const PARAM = 'count';

/**
 * "Contagens" (§5 inventory counts, §8.3): a blind count of one warehouse — create, start, record what was counted
 * (the expected quantity stays hidden until it is submitted), submit and approve. The API has no list of counts, so
 * the open one is kept in `?count=`.
 */
export function CountsTab({ storeId }: { storeId: string }) {
  const [params, setParams] = useSearchParams();
  const countId = params.get(PARAM);
  const canCount = usePermission('INVENTORY_COUNT', storeId);
  const open = (id: string) =>
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous);
        next.set(PARAM, id);
        return next;
      },
      { replace: true },
    );
  return (
    <div className="flex flex-col gap-6">
      {canCount && <NewCount storeId={storeId} onCreated={open} />}
      {countId ? (
        <CountCard key={countId} countId={countId} storeId={storeId} />
      ) : (
        <p className="text-muted-foreground">Crie uma contagem para um armazém; ela fica aberta aqui até ser aprovada.</p>
      )}
    </div>
  );
}

function NewCount({ storeId, onCreated }: { storeId: string; onCreated: (id: string) => void }) {
  const warehouses = useWarehouses(storeId);
  const create = useCreateCount();
  const [warehouseId, setWarehouseId] = useState('');
  const [intent, setIntent] = useState<Intent<CountRequest>>();
  const send = (body: CountRequest) => {
    const current = nextIntent(intent, body, () => crypto.randomUUID());
    setIntent(current);
    create.mutate(current, { onSuccess: (count) => count.id && onCreated(count.id) });
  };
  return (
    <Card className="gap-4">
      <CardHeader>
        <CardTitle className="text-lg">Nova contagem</CardTitle>
        <CardDescription>Uma contagem cega de todo o armazém: as quantidades esperadas só aparecem depois de submetida.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-wrap items-end gap-4">
          <label className="flex flex-col gap-1 text-sm font-medium">
            Armazém a contar
            <select className={SELECT} value={warehouseId} onChange={(event) => setWarehouseId(event.target.value)}>
              <option value="">Escolha…</option>
              {warehouses.data?.map((warehouse) => (
                <option key={warehouse.id} value={warehouse.id}>
                  {warehouse.name}
                </option>
              ))}
            </select>
          </label>
          <ConfirmButton
            pending={create.isPending}
            disabled={!warehouseId}
            onClick={() => send({ warehouseId, kind: 'REGULAR', scope: 'ALL' })}
          >
            <ClipboardList aria-hidden="true" />
            Nova contagem
          </ConfirmButton>
        </div>
        <CommandError error={create.error} pending={create.isPending} onRetry={() => intent && send(intent.body)} />
      </CardContent>
    </Card>
  );
}

function CountCard({ countId, storeId }: { countId: string; storeId: string }) {
  const count = useCount(countId);
  const items = useStockItems();
  const action = useRowCommand(useCountAction());
  const canCount = usePermission('INVENTORY_COUNT', storeId);
  const canApprove = usePermission('INVENTORY_COUNT_APPROVE', storeId);
  const view = count.data;
  const warehouses = useWarehouses(storeId);
  const warehouse = warehouses.data?.find((candidate) => candidate.id === view?.warehouseId)?.name ?? '—';
  const item = (id?: string) => items.data?.find((candidate) => candidate.id === id)?.name ?? '—';
  const run = (name: 'start' | 'submit' | 'approve') => action.run({ id: countId, action: name, label: warehouse });
  const submitted = view?.status !== 'DRAFT' && view?.status !== 'IN_PROGRESS';

  return (
    <Card className="gap-4">
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle className="flex flex-wrap items-center gap-2 text-lg">
          Contagem de {warehouse}
          {view && <StateBadge status={view.status} states={countStates} />}
        </CardTitle>
        <div className="flex flex-wrap gap-2">
          {canCount && view?.status === 'DRAFT' && (
            <ConfirmButton pending={action.pending} onClick={() => run('start')}>
              <Play aria-hidden="true" />
              Iniciar contagem
            </ConfirmButton>
          )}
          {canCount && view?.status === 'IN_PROGRESS' && (
            <ConfirmButton pending={action.pending} onClick={() => run('submit')}>
              <Send aria-hidden="true" />
              Submeter contagem
            </ConfirmButton>
          )}
          {canApprove && view?.status === 'SUBMITTED' && (
            <ConfirmButton pending={action.pending} onClick={() => run('approve')}>
              <ClipboardCheck aria-hidden="true" />
              Aprovar contagem
            </ConfirmButton>
          )}
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <CommandError error={action.error} pending={action.pending} onRetry={action.retry} />
        <QueryState
          query={count}
          pending={count.isPending}
          empty={!view?.lines?.length}
          emptyMessage={view?.status === 'DRAFT' ? 'Inicie a contagem para ver os artigos do armazém.' : 'Sem artigos nesta contagem.'}
        >
          <Table>
            <TableCaption className="sr-only">Linhas da contagem</TableCaption>
            <TableHeader>
              <TableRow>
                <TableHead>Artigo</TableHead>
                <TableHead>Contado</TableHead>
                {submitted && <TableHead className="text-right">Esperado</TableHead>}
                {submitted && <TableHead className="text-right">Diferença</TableHead>}
              </TableRow>
            </TableHeader>
            <TableBody>
              {view?.lines?.map((line) => (
                <TableRow key={line.stockItemId}>
                  <TableCell className="font-medium">{item(line.stockItemId)}</TableCell>
                  <TableCell>
                    {view.status === 'IN_PROGRESS' && canCount ? (
                      <CountLineInput countId={countId} line={line} name={item(line.stockItemId)} />
                    ) : (
                      <span className="tabular-nums">{formatQuantity(line.countedQuantity, line.unit)}</span>
                    )}
                  </TableCell>
                  {submitted && <TableCell className="text-right tabular-nums">{formatQuantity(line.expectedQuantity, line.unit)}</TableCell>}
                  {submitted && <TableCell className="text-right tabular-nums">{formatQuantity(line.difference, line.unit)}</TableCell>}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </QueryState>
      </CardContent>
    </Card>
  );
}

type Line = NonNullable<CountView['lines']>[number];

/** What was counted of one item, recorded on its own (again while in progress; a PUT, the same value twice is the same). */
function CountLineInput({ countId, line, name }: { countId: string; line: Line; name: string }) {
  const record = useRecordCountLine();
  const [text, setText] = useState(line.countedQuantity ?? '');
  const [invalid, setInvalid] = useState(false);
  const save = () => {
    const countedQuantity = toDecimal(text);
    setInvalid(countedQuantity === undefined);
    if (countedQuantity !== undefined) record.mutate({ countId, stockItemId: line.stockItemId!, countedQuantity });
  };
  const errorId = `count-error-${line.stockItemId}`;
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center gap-2">
        <Input
          aria-label={`Contado de ${name}`}
          aria-invalid={invalid}
          aria-describedby={invalid ? errorId : undefined}
          inputMode="decimal"
          className="max-w-32"
          value={text}
          onChange={(event) => setText(event.target.value)}
        />
        <span className="text-sm text-muted-foreground">{line.unit}</span>
        <Button size="sm" variant="outline" aria-label={`Registar ${name}`} disabled={record.isPending} onClick={save}>
          Registar
        </Button>
      </div>
      {invalid && (
        <p id={errorId} className="text-sm text-error">
          Indique uma quantidade, por exemplo 2,5.
        </p>
      )}
      <CommandError error={record.error} pending={record.isPending} onRetry={save} />
    </div>
  );
}
