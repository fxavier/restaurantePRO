import {
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  ConfirmButton,
  DataState,
  LOCALE,
  StatusBadge,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { Plus, Rocket, ShieldCheck } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { useSession } from '../../identity';
import { releaseStatusesFor, warningsLabel } from '../catalog-labels';
import { useCreateRelease, useValidateRelease } from '../hooks/use-catalog-commands';
import { useReleases, useStore } from '../hooks/use-catalog-queries';
import { canPublish } from '../release-commands';
import type { ReleaseSummary } from '../types';
import { CommandAlert } from './form-parts';
import { LoadError } from './load-error';
import { PublishDialog } from './publish-dialog';
import { ResumePublication } from './resume-publication';
import { ForStore } from './store-required';

/** "Versões": the store's catalog releases (spec §8.3), and starting, validating and publishing one. */
export function ReleasesTab({ storeId }: { storeId: string | undefined }) {
  return <ForStore storeId={storeId} what="as versões do catálogo">{(id) => <StoreReleases storeId={id} />}</ForStore>;
}

function StoreReleases({ storeId }: { storeId: string }) {
  const releases = useReleases(storeId);
  const create = useCreateRelease(storeId);
  const validate = useValidateRelease(storeId);
  const [toPublish, setToPublish] = useState<string | undefined>();

  if (releases.isError) {
    return <LoadError error={releases.error} onRetry={() => void releases.refetch()} forbidden="Sem permissão para ver as versões desta loja." />;
  }
  return (
    <div className="flex flex-col gap-4">
      <ResumePublication storeId={storeId} />
      <Card>
        <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-3">
          <div className="flex flex-col gap-1">
            <CardTitle className="text-lg">Versões do catálogo</CardTitle>
            <CardDescription>
              Rascunho → Validada → Publicada → Retirada. Publicar uma versão não altera pedidos nem documentos já
              registados.
            </CardDescription>
          </div>
          {releases.data && (
            <ConfirmButton pending={create.isPending} onClick={() => create.mutate(undefined)}>
              <Plus aria-hidden="true" />
              Nova versão
            </ConfirmButton>
          )}
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {/* No Idempotency-Key on POST /releases: a resend could make a second draft. The list, read again, shows whether it exists. */}
          <FailedCommand command={create} />
          <FailedCommand command={validate} retry={() => validate.variables && validate.mutate(validate.variables)} />
          <ReleasesList
            storeId={storeId}
            loading={releases.isPending}
            releases={releases.data?.content ?? []}
            validating={validate.isPending}
            onValidate={(releaseId) => validate.mutate(releaseId)}
            onPublish={setToPublish}
          />
        </CardContent>
      </Card>
      <PublishDialog releaseId={toPublish} storeId={storeId} onClose={() => setToPublish(undefined)} />
    </div>
  );
}

type ReleasesListProps = {
  storeId: string;
  loading: boolean;
  releases: ReleaseSummary[];
  validating: boolean;
  onValidate: (releaseId: string) => void;
  onPublish: (releaseId: string | undefined) => void;
};

function ReleasesList({ storeId, loading, releases, validating, onValidate, onPublish }: ReleasesListProps) {
  const timeZone = useStore(storeId).data?.timezone;
  const publishAllowed = canPublish(useSession().data?.permissions ?? [], storeId);
  return (
    <DataState state={loading ? 'loading' : releases.length ? 'ready' : 'empty'} emptyMessage="Esta loja ainda não tem versões do catálogo.">
      <ReleasesTable
        releases={releases}
        timeZone={timeZone}
        actions={(release) => (
          <ReleaseActions
            release={release}
            validating={validating}
            publishAllowed={publishAllowed}
            onValidate={() => onValidate(release.id ?? '')}
            onPublish={() => onPublish(release.id)}
          />
        )}
      />
    </DataState>
  );
}

/** A release command's failure, with "Tentar novamente" when `retry` is given and resending can help; nothing while it has not failed. */
function FailedCommand({ command, retry }: { command: { error: unknown; isPending: boolean }; retry?: () => void }) {
  if (!command.error) return null;
  return <CommandAlert error={command.error} pending={command.isPending} onRetry={retry} />;
}

type ReleasesTableProps = {
  releases: ReleaseSummary[];
  timeZone: string | undefined;
  actions: (release: ReleaseSummary) => ReactNode;
};

function ReleasesTable({ releases, timeZone, actions }: ReleasesTableProps) {
  return (
    <Table>
      <TableCaption className="sr-only">Versões do catálogo da loja</TableCaption>
      <TableHeader>
        <TableRow>
          <TableHead>Estado</TableHead>
          <TableHead>Criada</TableHead>
          <TableHead>Validada</TableHead>
          <TableHead>Publicada</TableHead>
          <TableHead>Avisos</TableHead>
          <TableHead>
            <span className="sr-only">Ações</span>
          </TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {releases.map((release) => (
          <TableRow key={release.id}>
            <TableCell>
              <StatusBadge status={release.status ?? ''} statuses={releaseStatusesFor(release.status ?? '')} />
            </TableCell>
            <TableCell className="tabular-nums">{instant(release.createdAt, timeZone)}</TableCell>
            <TableCell className="tabular-nums">{instant(release.validatedAt, timeZone)}</TableCell>
            <TableCell className="tabular-nums">{instant(release.effectiveAt, timeZone)}</TableCell>
            <TableCell>
              <Warnings release={release} />
            </TableCell>
            <TableCell>{actions(release)}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

type ReleaseActionsProps = {
  release: ReleaseSummary;
  validating: boolean;
  publishAllowed: boolean;
  onValidate: () => void;
  onPublish: () => void;
};

/** What §8.3 allows from the release's state: validate a DRAFT or VALIDATED one, publish a VALIDATED one. */
function ReleaseActions({ release, validating, publishAllowed, onValidate, onPublish }: ReleaseActionsProps) {
  const validatable = release.status === 'DRAFT' || release.status === 'VALIDATED';
  return (
    <div className="flex justify-end gap-2">
      {validatable && (
        <Button variant="outline" size="sm" disabled={validating} onClick={onValidate}>
          <ShieldCheck aria-hidden="true" />
          Validar
        </Button>
      )}
      {release.status === 'VALIDATED' && publishAllowed && (
        <Button size="sm" onClick={onPublish}>
          <Rocket aria-hidden="true" />
          Publicar
        </Button>
      )}
    </div>
  );
}

function Warnings({ release }: { release: ReleaseSummary }) {
  const warnings = release.validationWarnings ?? [];
  if (!warnings.length) return <span className="text-muted-foreground">—</span>;
  return (
    <details className="text-sm">
      <summary className="cursor-pointer rounded-sm font-medium text-warning focus-visible:outline-2 focus-visible:outline-ring">
        {warningsLabel(warnings.length)}
      </summary>
      <ul className="mt-2 flex flex-col gap-1 text-muted-foreground">
        {warnings.map((warning, index) => (
          <li key={`${warning.code}-${warning.itemId ?? index}`}>
            {warning.itemCode ? `${warning.itemCode} · ${warning.message}` : warning.message}
          </li>
        ))}
      </ul>
    </details>
  );
}

/** An instant in the store's timezone (FC §12); nothing until the timezone is known, never the browser's. */
function instant(at: string | undefined, timeZone: string | undefined): string {
  if (!at) return '—';
  if (!timeZone) return '…';
  return new Intl.DateTimeFormat(LOCALE, { dateStyle: 'short', timeStyle: 'short', timeZone }).format(new Date(at));
}
