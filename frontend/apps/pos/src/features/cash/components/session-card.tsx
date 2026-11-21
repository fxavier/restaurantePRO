import { Badge, Money } from '@restaurantpro/ui';
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
 * The operator's open session (Stitch's header card): the register, the operator, when it opened, the business day and
 * the opening float. What the session expects is never shown here: the count is blind (P23).
 */
export function SessionCard({ session, register, operator, timeZone, children }: Props) {
  const title = useId();
  return (
    <section aria-labelledby={title} className="flex flex-col gap-4 rounded-xl border bg-card p-4 lg:p-6">
      <div className="flex flex-wrap items-start gap-4">
        <div className="flex size-14 shrink-0 items-center justify-center rounded-lg bg-muted text-primary-hover">
          <Wallet aria-hidden="true" className="size-8" />
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-2">
          <div className="flex flex-wrap items-center gap-3">
            <h3 id={title} className="text-2xl font-semibold">
              {register ? `${register.name} (${register.code})` : 'Caixa'}
            </h3>
            <Badge variant="success">
              <LockOpen aria-hidden="true" />
              Sessão aberta
            </Badge>
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
        <div role="group" aria-label="Fundo de caixa inicial" className="flex flex-col gap-1 rounded-lg bg-muted p-4 sm:w-64">
          <span className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">Fundo de caixa inicial</span>
          <Money value={session.openingFloat} className="text-2xl font-semibold" />
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
