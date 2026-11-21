import {
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  ConfirmButton,
  Input,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { ClipboardCheck, ClipboardList, Play, Send, type LucideIcon } from 'lucide-react';
import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { toDecimal } from '../decimal';
import { formatQuantity } from '../format';
import { useCountAction, useCreateCount, useRecordCountLine } from '../hooks/use-inventory-commands';
import { useCount, useNames, useWarehouses } from '../hooks/use-inventory-reads';
import { usePermission } from '../hooks/use-permission';
import { useRowCommand } from '../hooks/use-row-command';
import { nextIntent, type Intent } from '../intent';
import type { CountRequest, CountView } from '../types';
import { CommandError } from './command-error';
import { SELECT } from './command-form';
import { QueryState } from './query-state';
import { ScrollTable } from './scroll-table';
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

/** The one command each live state offers, and who may give it (§8.3: DRAFT → IN_PROGRESS → SUBMITTED → APPROVED). */
const NEXT_STEP: Record<string, { action: 'start' | 'submit' | 'approve'; label: string; icon: LucideIcon; permission: string }> = {
  DRAFT: { action: 'start', label: 'Iniciar contagem', icon: Play, permission: 'INVENTORY_COUNT' },
  IN_PROGRESS: { action: 'submit', label: 'Submeter contagem', icon: Send, permission: 'INVENTORY_COUNT' },
  SUBMITTED: { action: 'approve', label: 'Aprovar contagem', icon: ClipboardCheck, permission: 'INVENTORY_COUNT_APPROVE' },
};

function CountCard({ countId, storeId }: { countId: string; storeId: string }) {
  const count = useCount(countId);
  if (count.data) return <OpenCount count={count.data} storeId={storeId} />;
  return (
    <Card>
      <CardContent>
        <QueryState query={count} pending={count.isPending} empty={false} emptyMessage="">
          {null}
        </QueryState>
      </CardContent>
    </Card>
  );
}

/** A loaded count: its state, the one command it offers next, and its lines. */
function OpenCount({ count, storeId }: { count: CountView; storeId: string }) {
  const names = useNames(storeId);
  const action = useRowCommand(useCountAction());
  const status = count.status ?? '';
  const next = NEXT_STEP[status];
  const allowed = usePermission(next?.permission ?? 'INVENTORY_COUNT', storeId);
  const editable = usePermission('INVENTORY_COUNT', storeId) && status === 'IN_PROGRESS';
  const warehouse = names.warehouseName(count.warehouseId);

  return (
    <Card className="gap-4">
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle className="flex flex-wrap items-center gap-2 text-lg">
          Contagem de {warehouse}
          <StateBadge status={status} states={countStates} />
        </CardTitle>
        {next && allowed && (
          <ConfirmButton pending={action.pending} onClick={() => action.run({ id: count.id!, action: next.action, label: warehouse })}>
            <next.icon aria-hidden="true" />
            {next.label}
          </ConfirmButton>
        )}
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <CommandError error={action.error} pending={action.pending} onRetry={action.retry} />
        {count.lines?.length ? (
          <CountLines count={count} editable={editable} itemName={names.itemName} />
        ) : (
          <p className="py-6 text-center text-muted-foreground">
            {status === 'DRAFT' ? 'Inicie a contagem para ver os artigos do armazém.' : 'Sem artigos nesta contagem.'}
          </p>
        )}
      </CardContent>
    </Card>
  );
}

/** The count's lines: what was counted (editable while in progress); expected and difference once submitted (blind). */
function CountLines({ count, editable, itemName }: { count: CountView; editable: boolean; itemName: (id?: string) => string }) {
  const revealed = !NEXT_STEP[count.status ?? ''] || count.status === 'SUBMITTED';
  return (
    <ScrollTable label="Linhas da contagem">
      <TableHeader>
        <TableRow>
          <TableHead>Artigo</TableHead>
          <TableHead>Contado</TableHead>
          {revealed && <TableHead className="text-right">Esperado</TableHead>}
          {revealed && <TableHead className="text-right">Diferença</TableHead>}
        </TableRow>
      </TableHeader>
      <TableBody>
        {count.lines?.map((line) => (
          <TableRow key={line.stockItemId}>
            <TableCell className="font-medium">{itemName(line.stockItemId)}</TableCell>
            <TableCell>
              {editable ? (
                <CountLineInput countId={count.id!} line={line} name={itemName(line.stockItemId)} />
              ) : (
                <span className="tabular-nums">{formatQuantity(line.countedQuantity, line.unit)}</span>
              )}
            </TableCell>
            {revealed && <TableCell className="text-right tabular-nums">{formatQuantity(line.expectedQuantity, line.unit)}</TableCell>}
            {revealed && <TableCell className="text-right tabular-nums">{formatQuantity(line.difference, line.unit)}</TableCell>}
          </TableRow>
        ))}
      </TableBody>
    </ScrollTable>
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
