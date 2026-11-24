import { Money, StatusPill } from '@restaurantpro/ui';
import { CalendarDays, CircleUser, Clock, LockOpen, Wallet } from 'lucide-react';
import { useId, type ReactNode } from 'react';
import { businessDay, clockTime } from '../format';
import type { RegisterSummary, SessionDetails } from '../hooks/use-cash';

type Props = {
  session: SessionDetails;
  register: RegisterSummary | undefined;
  operator: string;
  timeZone: string;
  /** The session's commands (cash in, out, expense, drawer). */
  children?: ReactNode;
};

/**
 * The operator's open session (the light reference's white card): the register, the operator, when it opened, the
 * business day and the opening float. What the session expects is never shown here: the count is blind (P23).
 */
export function SessionCard({ session, register, operator, timeZone, children }: Props) {
  const title = useId();
  return (
    <section aria-labelledby={title} className="flex flex-col gap-4 rounded-2xl border bg-card p-4">
      <div className="flex flex-wrap items-start gap-4">
        <div className="flex size-14 shrink-0 items-center justify-center rounded-xl border-2 border-success-border bg-success-tint text-success-text">
          <Wallet aria-hidden="true" className="size-8" />
        </div>
        {/* A basis, so the commands wrap under the title rather than squeeze it to nothing. */}
        <div className="flex min-w-0 flex-1 basis-72 flex-col gap-2">
          <div className="flex flex-wrap items-center gap-3">
            <h3 id={title} className="text-2xl font-semibold">
              {register ? `${register.name} (${register.code})` : 'Caixa'}
            </h3>
            <StatusPill tone="success" icon={LockOpen}>
              Sessão aberta
            </StatusPill>
          </div>
          <dl className="flex flex-wrap gap-x-6 gap-y-1 text-muted-foreground">
            <Detail icon={<CircleUser aria-hidden="true" className="size-5" />} term="Operador">
              {operator}
            </Detail>
            {session.openedAt && (
              <Detail icon={<Clock aria-hidden="true" className="size-5" />} term="Abertura">
                {clockTime(session.openedAt, timeZone)}
              </Detail>
            )}
            {session.businessDate && (
              <Detail icon={<CalendarDays aria-hidden="true" className="size-5" />} term="Dia comercial">
                {businessDay(session.businessDate)}
              </Detail>
            )}
          </dl>
        </div>
        {children && <div className="flex flex-wrap gap-2">{children}</div>}
      </div>
      {session.openingFloat && (
        <div role="group" aria-label="Fundo de caixa inicial" className="flex flex-col gap-1 rounded-xl bg-muted px-4 py-3 sm:w-64">
          <span className="text-sm text-muted-foreground">Fundo de caixa inicial</span>
          <Money value={session.openingFloat} className="text-2xl font-bold" />
        </div>
      )}
    </section>
  );
}

function Detail({ icon, term, children }: { icon: ReactNode; term: string; children: ReactNode }) {
  return (
    <div className="flex items-center gap-2">
      {icon}
      <dt>{term}:</dt>
      <dd className="font-semibold text-foreground">{children}</dd>
    </div>
  );
}
