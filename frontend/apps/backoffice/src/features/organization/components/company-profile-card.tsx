import { Badge, Card, CardContent, CardHeader, CardTitle, DataState } from '@restaurantpro/ui';
import { Building2, ShieldCheck } from 'lucide-react';
import { useId } from 'react';
import { useSession } from '../../identity';
import { isUnavailable } from '../../../lib/errors';
import { isStepUpRequired } from '../../../lib/step-up';
import { useCompanyProfile } from '../hooks/use-company-profile';
import { EditCompanyProfileDialog } from './edit-company-profile-dialog';
import { Facts } from './facts';
import { StepUpButton } from './step-up-button';

const COMPANY_MANAGE = 'PERM_COMPANY_MANAGE';

/**
 * The company's legal profile (Stitch's company card): name, tax number, country and currency, and "Editar perfil".
 * Only for a user who holds COMPANY_MANAGE (a convenience; the API decides), a permission that asks for a recent second
 * factor even to read.
 */
export function CompanyProfileCard() {
  const { data: session } = useSession();
  const allowed = session?.permissions.includes(COMPANY_MANAGE) ?? false;
  const profile = useCompanyProfile(allowed);
  const titleId = useId();
  if (!allowed) return null;

  if (isStepUpRequired(profile.error)) {
    return (
      <Card className="mt-6">
        <CardContent className="flex flex-col items-center gap-3 text-center">
          <ShieldCheck aria-hidden="true" className="size-8 text-muted-foreground" />
          <p>Para ver o perfil da empresa, confirme a sua identidade.</p>
          <StepUpButton error={profile.error} />
        </CardContent>
      </Card>
    );
  }
  if (!profile.data) {
    return (
      <Card className="mt-6">
        <CardContent>
          <DataState
            state={profile.isError ? (isUnavailable(profile.error) ? 'unavailable' : 'error') : 'loading'}
            onRetry={() => void profile.refetch()}
          >
            {null}
          </DataState>
        </CardContent>
      </Card>
    );
  }

  const company = profile.data;
  return (
    <Card role="region" aria-labelledby={titleId} className="mt-6 gap-4">
      <CardHeader className="flex flex-row flex-wrap items-start gap-3">
        <span className="flex size-12 shrink-0 items-center justify-center rounded-lg bg-muted">
          <Building2 aria-hidden="true" className="size-6 text-primary" />
        </span>
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <CardTitle id={titleId} className="text-xl">
            {company.name}
          </CardTitle>
          <Badge variant="secondary" className="w-fit">
            Perfil da empresa
          </Badge>
        </div>
        <EditCompanyProfileDialog profile={company} />
      </CardHeader>
      <CardContent>
        <Facts
          className="sm:grid-cols-3"
          facts={[
            ['NUIT / NIF', company.taxId],
            ['País', company.country],
            ['Moeda', company.currency],
          ]}
        />
      </CardContent>
    </Card>
  );
}
