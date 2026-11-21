import {
  Button,
  DataState,
  StatusBadge,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  type StatusMap,
} from '@restaurantpro/ui';
import { CirclePause, CirclePlay, CircleCheck, ArrowUpDown } from 'lucide-react';
import { useId } from 'react';
import { dataState } from '../data-state';
import { useAdjustPoints, useLoyalty, useLoyaltyCommand, useLoyaltyStatement } from '../hooks/use-loyalty';
import { formatBusinessDate, formatPoints, formatPointsChange, loyaltyKindLabel } from '../labels';
import type { Allowed } from '../permissions';
import { adjustmentSchema, reasonSchema } from '../schemas';
import type { LoyaltyBalance } from '../types';
import { CommandDialog, ReasonDialog, TextField } from './command-dialog';
import { QueryFailure } from './query-failure';

const accountStatuses: StatusMap<'ACTIVE' | 'SUSPENDED'> = {
  ACTIVE: { label: 'Conta ativa', icon: CircleCheck, tone: 'success' },
  SUSPENDED: { label: 'Conta suspensa', icon: CirclePause, tone: 'warning' },
};

type LoyaltyTabProps = { customerId: string; allowed: Allowed; storeId: string | undefined };

/** "Fidelização": the points and the account's state, its suspend, resume and manual adjustment, and the movements. */
export function LoyaltyTab({ customerId, allowed, storeId }: LoyaltyTabProps) {
  const loyalty = useLoyalty(customerId);
  if (loyalty.isError) return <QueryFailure error={loyalty.error} onRetry={() => void loyalty.refetch()} />;
  if (!loyalty.data) return <DataState state="loading">{null}</DataState>;
  if (!loyalty.data.enrolled) return <p className="py-6 text-center">O cliente não aderiu à fidelização. A adesão faz-se no POS.</p>;
  return (
    <div className="flex flex-col gap-6">
      <LoyaltyAccount account={loyalty.data} />
      <LoyaltyCommands customerId={customerId} account={loyalty.data} allowed={allowed} storeId={storeId} />
      <LoyaltyStatement customerId={customerId} />
    </div>
  );
}

function LoyaltyAccount({ account }: { account: LoyaltyBalance }) {
  const status = account.status === 'SUSPENDED' ? 'SUSPENDED' : 'ACTIVE';
  return (
    <div className="flex flex-wrap items-center gap-4 rounded-lg border bg-muted p-4">
      <p className="text-3xl font-bold tabular-nums">{formatPoints(account.points)} pontos</p>
      <StatusBadge status={status} statuses={accountStatuses} />
    </div>
  );
}

function LoyaltyCommands({ customerId, account, allowed, storeId }: LoyaltyTabProps & { account: LoyaltyBalance }) {
  const hintId = useId();
  if (!allowed.LOYALTY_ENROLL && !allowed.LOYALTY_ADJUST) return null;
  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap gap-2">
        {allowed.LOYALTY_ENROLL &&
          <AccountStateDialog customerId={customerId} command={account.status === 'SUSPENDED' ? 'resume' : 'suspend'} />}
        {allowed.LOYALTY_ADJUST &&
          (storeId ? (
            <AdjustDialog customerId={customerId} storeId={storeId} />
          ) : (
            <Button variant="outline" disabled aria-describedby={hintId}>
              <ArrowUpDown aria-hidden="true" />
              Ajustar pontos
            </Button>
          ))}
      </div>
      {allowed.LOYALTY_ADJUST && !storeId && (
        <p id={hintId} className="text-sm text-muted-foreground">
          Escolha uma loja na barra superior para ajustar pontos.
        </p>
      )}
    </div>
  );
}

/** Suspend and resume differ only in what they say; both carry a reason (ReasonRequest). */
const accountCommands = {
  suspend: {
    icon: CirclePause,
    variant: 'destructive',
    trigger: 'Suspender',
    title: 'Suspender a conta de fidelização',
    description: 'Enquanto estiver suspensa, a conta não acumula nem resgata pontos. Os pontos mantêm-se.',
    submitLabel: 'Suspender conta',
  },
  resume: {
    icon: CirclePlay,
    variant: 'outline',
    trigger: 'Reativar',
    title: 'Reativar a conta de fidelização',
    description: 'A conta volta a acumular e a resgatar pontos.',
    submitLabel: 'Reativar conta',
  },
} as const;

function AccountStateDialog({ customerId, command }: { customerId: string; command: keyof typeof accountCommands }) {
  const runner = useLoyaltyCommand(customerId, command);
  const { icon: Icon, variant, trigger, ...text } = accountCommands[command];
  return (
    <ReasonDialog
      trigger={
        <Button variant={variant}>
          <Icon aria-hidden="true" />
          {trigger}
        </Button>
      }
      {...text}
      schema={reasonSchema}
      runner={runner}
    />
  );
}

/** A manual adjustment in the name of the store picked in the top bar (D42: authorised there). */
function AdjustDialog({ customerId, storeId }: { customerId: string; storeId: string }) {
  const runner = useAdjustPoints(customerId, storeId);
  return (
    <CommandDialog
      trigger={
        <Button variant="outline">
          <ArrowUpDown aria-hidden="true" />
          Ajustar pontos
        </Button>
      }
      title="Ajustar pontos"
      description="Um crédito ou um débito de pontos, com o motivo, em nome da loja escolhida. Um débito leva o sinal menos."
      submitLabel="Ajustar"
      schema={adjustmentSchema}
      defaultValues={{ points: '', reason: '' }}
      runner={runner}
      fields={(form) => (
        <>
          <TextField form={form} name="points" label="Pontos" inputMode="numeric" className="max-w-32 text-right tabular-nums" />
          <TextField form={form} name="reason" label="Motivo" maxLength={500} />
        </>
      )}
    />
  );
}

function LoyaltyStatement({ customerId }: { customerId: string }) {
  const statement = useLoyaltyStatement(customerId);
  if (statement.isError) return <QueryFailure error={statement.error} onRetry={() => void statement.refetch()} />;
  return (
    <section aria-label="Movimentos" className="flex flex-col gap-3">
      <h3 className="text-lg font-semibold">Movimentos de pontos</h3>
      <DataState state={dataState(statement, (s) => !s.lines?.length)} emptyMessage="Sem movimentos de pontos.">
        <Table>
          <TableCaption className="sr-only">Movimentos de pontos</TableCaption>
          <TableHeader>
            <TableRow>
              <TableHead>Data</TableHead>
              <TableHead>Tipo</TableHead>
              <TableHead>Motivo</TableHead>
              <TableHead className="text-right">Pontos</TableHead>
              <TableHead className="text-right">Saldo</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {statement.data?.lines?.map((line) => (
              <TableRow key={line.id}>
                <TableCell className="whitespace-nowrap">{formatBusinessDate(line.businessDate)}</TableCell>
                <TableCell>{loyaltyKindLabel(line.kind)}</TableCell>
                <TableCell className="whitespace-normal">{line.reason}</TableCell>
                <TableCell className="text-right tabular-nums">{formatPointsChange(line.points)}</TableCell>
                <TableCell className="text-right tabular-nums">{formatPoints(line.balance)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </DataState>
    </section>
  );
}
