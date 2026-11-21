import { Card, CardContent, CardDescription, CardHeader, CardTitle, cn, Money } from '@restaurantpro/ui';
import type { ReactNode } from 'react';
import type { Money as MoneyValue } from '../types';

type SectionCardProps = {
  title: string;
  description?: string;
  /** Beside the title: the section's filter or its create action. */
  action?: ReactNode;
  className?: string;
  children: ReactNode;
};

/** One of Stitch's titled blocks: a card with its title, a line of context, an optional action and its content. */
export function SectionCard({ title, description, action, className, children }: SectionCardProps) {
  return (
    <Card className={cn('min-w-0 gap-4', className)}>
      <CardHeader className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1.5">
          <CardTitle className="text-lg">{title}</CardTitle>
          {description && <CardDescription>{description}</CardDescription>}
        </div>
        {action}
      </CardHeader>
      <CardContent className="flex flex-col gap-4">{children}</CardContent>
    </Card>
  );
}

/** An amount as the server sent it, or a dash when it sent none; never computed here (FC §12). */
export function Amount({ value }: { value: MoneyValue | undefined }) {
  return value ? <Money value={value} /> : <>—</>;
}
