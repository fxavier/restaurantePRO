import { Badge, Button, LOCALE, StatusBadge } from '@restaurantpro/ui';
import { Check, RefreshCw, RotateCcw } from 'lucide-react';
import { useId, useState, type ReactNode } from 'react';
import { useAcknowledgeJob, usePrintJobs, useRetryJob, JOB_PAGE_SIZE } from '../hooks/use-print-jobs';
import { jobFilters, jobStatuses, jobTitle, resolutions } from '../labels';
import { canAcknowledge, canRetry, type PrintJob } from '../parse';
import type { PrintJobStatus } from '../types';
import { NativeSelect } from './native-select';
import { ProblemAlert } from './problem-alert';
import { QueryState } from './query-state';

type PrintQueueProps = {
  storeId: string;
  timeZone: string | undefined;
  printerName: (id: string) => string;
  /** PRODUCTION_OPERATE in this store: only then are retry and acknowledge offered. */
  mayOperate: boolean;
};

/** Stitch's "Fila de trabalhos": the store's print jobs, newest first, with what may still be done about each. */
export function PrintQueue({ storeId, timeZone, printerName, mayOperate }: PrintQueueProps) {
  const titleId = useId();
  const [status, setStatus] = useState<PrintJobStatus | ''>('');
  const [page, setPage] = useState(0);
  const jobs = usePrintJobs(storeId, status || undefined, page);
  const commands = useJobCommands();

  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-3 rounded-xl border bg-card p-4">
      <QueueHeader
        titleId={titleId}
        status={status}
        onRefresh={() => void jobs.refetch()}
        onStatus={(next) => {
          setStatus(next);
          setPage(0);
        }}
      />
      {commands.failure ? <ProblemAlert error={commands.failure} pending={commands.busy} onRetry={commands.resend} /> : null}
      <QueryState
        query={jobs}
        subject="os trabalhos de impressão"
        isEmpty={(data) => data.jobs.length === 0}
        emptyMessage="Sem trabalhos de impressão."
      >
        {(data) => (
          <>
            <ul aria-label="Trabalhos de impressão" className="flex flex-col gap-2">
              {data.jobs.map((job) => (
                <JobItem key={job.id} job={job} timeZone={timeZone} printerName={printerName(job.printerId)}>
                  {mayOperate && <JobActions job={job} commands={commands} />}
                </JobItem>
              ))}
            </ul>
            <Pager page={page} pages={Math.ceil(data.totalElements / JOB_PAGE_SIZE)} onPage={setPage} />
          </>
        )}
      </QueryState>
    </section>
  );
}

type JobCommands = ReturnType<typeof useJobCommands>;

/** Retry and acknowledge: one key per tap (FC §8); "Tentar novamente" resends the failed tap's variables. */
function useJobCommands() {
  const retry = useRetryJob();
  const acknowledge = useAcknowledgeJob();
  const run = (command: typeof retry | typeof acknowledge, jobId: string) => {
    retry.reset();
    acknowledge.reset();
    command.mutate({ jobId, idempotencyKey: crypto.randomUUID() });
  };
  const failed = retry.error ? retry : acknowledge;
  return {
    failure: failed.error,
    busy: retry.isPending || acknowledge.isPending,
    retry: (jobId: string) => run(retry, jobId),
    acknowledge: (jobId: string) => run(acknowledge, jobId),
    resend: () => failed.variables && failed.mutate(failed.variables),
  };
}

type QueueHeaderProps = {
  titleId: string;
  status: PrintJobStatus | '';
  onRefresh: () => void;
  onStatus: (status: PrintJobStatus | '') => void;
};

function QueueHeader({ titleId, status, onRefresh, onStatus }: QueueHeaderProps) {
  const filterId = useId();
  return (
    <>
      <div className="flex items-center justify-between gap-2">
        <h2 id={titleId} className="text-lg font-semibold">
          Fila de trabalhos
        </h2>
        <Button variant="ghost" size="icon" aria-label="Atualizar a fila" onClick={onRefresh}>
          <RefreshCw aria-hidden="true" />
        </Button>
      </div>
      <div className="flex items-center gap-2">
        <label htmlFor={filterId} className="text-sm font-medium whitespace-nowrap">
          Estado dos trabalhos
        </label>
        <NativeSelect id={filterId} value={status} onChange={(event) => onStatus(event.target.value as PrintJobStatus | '')}>
          {jobFilters.map((filter) => (
            <option key={filter.value} value={filter.value}>
              {filter.label}
            </option>
          ))}
        </NativeSelect>
      </div>
    </>
  );
}

function JobActions({ job, commands }: { job: PrintJob; commands: JobCommands }) {
  return (
    <>
      {canAcknowledge(job) && (
        <Button variant="outline" size="sm" disabled={commands.busy} onClick={() => commands.acknowledge(job.id)}>
          <Check aria-hidden="true" />
          Confirmar impresso
        </Button>
      )}
      {canRetry(job) && (
        <Button variant="outline" size="sm" disabled={commands.busy} onClick={() => commands.retry(job.id)}>
          <RotateCcw aria-hidden="true" />
          Reenviar
        </Button>
      )}
    </>
  );
}

function Pager({ page, pages, onPage }: { page: number; pages: number; onPage: (page: number) => void }) {
  if (pages <= 1) return null;
  return (
    <nav aria-label="Páginas da fila" className="flex items-center justify-end gap-2 text-sm">
      <Button variant="outline" size="sm" disabled={page === 0} onClick={() => onPage(page - 1)}>
        Anterior
      </Button>
      <span>
        Página {page + 1} de {pages}
      </span>
      <Button variant="outline" size="sm" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>
        Seguinte
      </Button>
    </nav>
  );
}

type JobItemProps = { job: PrintJob; timeZone: string | undefined; printerName: string; children: ReactNode };

function JobItem({ job, timeZone, printerName, children }: JobItemProps) {
  const title = jobTitle(job.sourceType, job.destination);
  return (
    <li aria-label={title} className="flex flex-col gap-1.5 rounded-md border bg-muted p-3 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium">{title}</span>
        {job.kind === 'COPY' && <Badge variant="outline">Cópia</Badge>}
        <span className="ml-auto">
          {job.resolution ? (
            <StatusBadge status={job.resolution} statuses={resolutions} />
          ) : (
            <StatusBadge status={job.status} statuses={jobStatuses} />
          )}
        </span>
      </div>
      <div className="flex flex-wrap gap-x-3 text-muted-foreground">
        <span>{printerName}</span>
        {timeZone && <JobTime iso={job.createdAt} timeZone={timeZone} />}
        {job.attempts > 1 && <span>{job.attempts} tentativas</span>}
      </div>
      {job.lastError && !job.resolution && <p className="font-mono text-xs text-error">{job.lastError}</p>}
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
