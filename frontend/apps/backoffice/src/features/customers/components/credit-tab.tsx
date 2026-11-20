import { ApiError } from '@restaurantpro/api-client';
import { DataState, Money, StatusBadge, type MoneyValue, type StatusMap } from '@restaurantpro/ui';
import { Ban, CircleCheck } from 'lucide-react';
import { useCredit } from '../hooks/use-customer-queries';
import type { CreditView } from '../types';
import { BlockCreditDialog, CreditLimitDialog, UnblockCreditDialog } from './credit-dialogs';
import { CreditStatement } from './credit-statement';
import { QueryFailure } from './query-failure';

const creditStatuses: StatusMap<'active' | 'blocked'> = {
  active: { label: 'Crédito ativo', icon: CircleCheck, tone: 'success' },
  blocked: { label: 'Crédito bloqueado', icon: Ban, tone: 'destructive' },
};

type CreditTabProps = { customerId: string; canManage: boolean };

/** "Crédito": the account as the server computed it, its limit and block, and the current-account statement. */
export function CreditTab({ customerId, canManage }: CreditTabProps) {
  const credit = useCredit(customerId);
  return (
    <div className="flex flex-col gap-6">
      <CreditAccountState customerId={customerId} credit={credit} canManage={canManage} />
      <CreditStatement customerId={customerId} />
    </div>
  );
}

type CreditQuery = ReturnType<typeof useCredit>;

function CreditAccountState({ customerId, credit, canManage }: CreditTabProps & { credit: CreditQuery }) {
  if (isAccountMissing(credit.error)) {
    return (
      <div className="flex flex-col items-start gap-3 rounded-lg border bg-muted p-4">
        <p>Este cliente ainda não tem conta de crédito.</p>
        {canManage && <CreditLimitDialog customerId={customerId} label="Definir limite" />}
      </div>
    );
  }
  if (credit.isError) return <QueryFailure error={credit.error} onRetry={() => void credit.refetch()} />;
  if (!credit.data) return <DataState state="loading">{null}</DataState>;
  return <CreditAccount customerId={customerId} credit={credit.data} canManage={canManage} />;
}

/** 422 CREDIT_ACCOUNT_MISSING: the first limit change opens the account. */
function isAccountMissing(error: unknown): boolean {
  return error instanceof ApiError && error.code === 'CREDIT_ACCOUNT_MISSING';
}

function CreditAccount({ customerId, credit, canManage }: CreditTabProps & { credit: CreditView }) {
  return (
    <div className="flex flex-col gap-4">
      <StatusBadge status={credit.blocked ? 'blocked' : 'active'} statuses={creditStatuses} />
      <ul aria-label="Conta de crédito" className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Figure label="Limite" value={credit.limit} />
        <Figure label="Dívida" value={credit.balance} />
        <Figure label="Reservado" value={credit.reserved} />
        <Figure label="Disponível" value={credit.available} />
      </ul>
      {canManage && (
        <div className="flex flex-wrap gap-2">
          <CreditLimitDialog customerId={customerId} label="Alterar limite" />
          {credit.blocked ? <UnblockCreditDialog customerId={customerId} /> : <BlockCreditDialog customerId={customerId} />}
        </div>
      )}
    </div>
  );
}

function Figure({ label, value }: { label: string; value: MoneyValue | undefined }) {
  return (
    <li className="flex flex-col gap-1 rounded-lg border bg-muted p-3">
      <span className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">{label}</span>
      <span className="text-xl font-bold">{value ? <Money value={value} /> : '—'}</span>
    </li>
  );
}
