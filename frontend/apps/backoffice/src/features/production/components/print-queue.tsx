import { Badge, Button, DataState, LOCALE, StatusBadge } from '@restaurantpro/ui';
import { Check, RefreshCw, RotateCcw } from 'lucide-react';
import { useId, useState, type ReactNode } from 'react';
import { useAcknowledgeJob, usePrintJobs, useRetryJob, JOB_PAGE_SIZE } from '../hooks/use-print-jobs';
import { jobFilters, jobStatuses, jobTitle, resolutions } from '../labels';
import { canAcknowledge, canRetry, type PrintJob } from '../parse';
import type { PrintJobStatus } from '../types';
import { NativeSelect } from './native-select';
import { ProblemAlert } from './problem-alert';
import { SectionError } from './section-error';

type PrintQueueProps = {
  storeId: string;
  timeZone: string | undefined;
  printerName: (id: string) => string;
  /** PRODUCTION_OPERATE in this store: only then are retry and acknowledge offered. */
  mayOperate: boolean;
};

/** Stitch's "Fila de trabalhos": the store's print jobs, newest first, with what may still be done about each. */
export function PrintQueue({ storeId, timeZone, printerName, mayOperate }: PrintQueueProps) {
  const filterId = useId();
  const [status, setStatus] = useState<PrintJobStatus | ''>('');
  const [page, setPage] = useState(0);
  const jobs = usePrintJobs(storeId, status || undefined, page);
  const retry = useRetryJob();
  const acknowledge = useAcknowledgeJob();
  const failure = retry.error ?? acknowledge.error;
  const busy = retry.isPending || acknowledge.isPending;
  const pages = Math.ceil((jobs.data?.totalElements ?? 0) / JOB_PAGE_SIZE);

  /** One key per tap (FC §8); "Tentar novamente" resends the same variables. */
  const run = (command: typeof retry | typeof acknowledge, jobId: string) => {
    retry.reset();
    acknowledge.reset();
    command.mutate({ jobId, idempotencyKey: crypto.randomUUID() });
  };
  const resend = () => {
    const failed = retry.error ? retry : acknowledge;
    if (failed.variables) failed.mutate(failed.variables);
  };

  return (
    <section aria-labelledby={`${filterId}-title`} className="flex flex-col gap-3 rounded-xl border bg-card p-4">
      <div className="flex items-center justify-between gap-2">
        <h2 id={`${filterId}-title`} className="text-lg font-semibold">
          Fila de trabalhos
        </h2>
        <Button variant="ghost" size="icon" aria-label="Atualizar a fila" onClick={() => void jobs.refetch()}>
          <RefreshCw aria-hidden="true" />
        </Button>
      </div>
      <div className="flex items-center gap-2">
        <label htmlFor={filterId} className="text-sm font-medium whitespace-nowrap">
          Estado dos trabalhos
        </label>
        <NativeSelect
          id={filterId}
          value={status}
          onChange={(event) => {
            setStatus(event.target.value as PrintJobStatus | '');
            setPage(0);
          }}
        >
          {jobFilters.map((filter) => (
            <option key={filter.value} value={filter.value}>
              {filter.label}
            </option>
          ))}
        </NativeSelect>
      </div>
      {failure ? <ProblemAlert error={failure} pending={busy} onRetry={resend} /> : null}
      {jobs.isError ? (
        <SectionError error={jobs.error} subject="os trabalhos de impressão" onRetry={() => void jobs.refetch()} />
      ) : (
        <DataState
          state={jobs.isPending ? 'loading' : jobs.data.jobs.length ? 'ready' : 'empty'}
          emptyMessage="Sem trabalhos de impressão."
        >
          <ul aria-label="Trabalhos de impressão" className="flex flex-col gap-2">
            {jobs.data?.jobs.map((job) => (
              <JobItem key={job.id} job={job} timeZone={timeZone} printerName={printerName(job.printerId)}>
                {mayOperate && canAcknowledge(job) && (
                  <Button variant="outline" size="sm" disabled={busy} onClick={() => run(acknowledge, job.id)}>
                    <Check aria-hidden="true" />
                    Confirmar impresso
                  </Button>
                )}
                {mayOperate && canRetry(job) && (
                  <Button variant="outline" size="sm" disabled={busy} onClick={() => run(retry, job.id)}>
                    <RotateCcw aria-hidden="true" />
                    Reenviar
                  </Button>
                )}
              </JobItem>
            ))}
          </ul>
          {pages > 1 && (
            <nav aria-label="Páginas da fila" className="flex items-center justify-end gap-2 text-sm">
              <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
                Anterior
              </Button>
              <span>
                Página {page + 1} de {pages}
              </span>
              <Button variant="outline" size="sm" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>
                Seguinte
              </Button>
            </nav>
          )}
        </DataState>
      )}
    </section>
  );
}

type JobItemProps = { job: PrintJob; timeZone: string | undefined; printerName: string; children: ReactNode };

function JobItem({ job, timeZone, printerName, children }: JobItemProps) {
  const title = jobTitle(job.sourceType, job.destination);
  const resolution = job.resolution ? resolutions[job.resolution] : undefined;
  return (
    <li aria-label={title} className="flex flex-col gap-1.5 rounded-md border bg-muted p-3 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium">{title}</span>
        {job.kind === 'COPY' && <Badge variant="outline">Cópia</Badge>}
        <span className="ml-auto">
          <StatusBadge status={job.status} statuses={jobStatuses} />
        </span>
      </div>
      <div className="flex flex-wrap gap-x-3 text-muted-foreground">
        <span>{printerName}</span>
        {timeZone && <JobTime iso={job.createdAt} timeZone={timeZone} />}
        {job.attempts > 1 && <span>{job.attempts} tentativas</span>}
      </div>
      {job.lastError && !resolution && <p className="font-mono text-xs text-error">{job.lastError}</p>}
      {resolution && <p className="text-muted-foreground">{resolution}</p>}
      <div className="flex flex-wrap justify-end gap-2 empty:hidden">{children}</div>
    </li>
  );
}

function JobTime({ iso, timeZone }: { iso: string; timeZone: string }) {
  const date = new Date(iso);
  const time = new Intl.DateTimeFormat(LOCALE, { timeZone, hour: '2-digit', minute: '2-digit' }).format(date);
  const full = new Intl.DateTimeFormat(LOCALE, { timeZone, dateStyle: 'short', timeStyle: 'medium' }).format(date);
  return (
    <time dateTime={iso} title={full}>
      {time}
    </time>
  );
}
