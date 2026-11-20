import { ApiError } from '@restaurantpro/api-client';
import { describe, expect, it } from 'vitest';
import { fieldErrors } from './form-errors';
import { jobTitle } from './labels';
import { canAcknowledge, canRetry, parseJobs, parsePrinters, type PrintJob } from './parse';
import { holdsIn } from './permissions';
import { createRequest, updateRequest } from './printer-requests';
import { routesSchema, type PrinterFormValues } from './schemas';

const STORE = '11111111-0000-4000-8000-000000000001';

describe('holdsIn', () => {
  it('grants a permission held company-wide or in this store, and nothing else', () => {
    expect(holdsIn(['PERM_PRODUCTION_OPERATE'], 'PRODUCTION_OPERATE', STORE)).toBe(true);
    expect(holdsIn([`PERM_PRODUCTION_OPERATE@${STORE}`], 'PRODUCTION_OPERATE', STORE)).toBe(true);
    expect(holdsIn(['PERM_PRODUCTION_OPERATE@other-store'], 'PRODUCTION_OPERATE', STORE)).toBe(false);
    expect(holdsIn(['PERM_PRODUCTION_MANAGE'], 'PRODUCTION_OPERATE', STORE)).toBe(false);
    expect(holdsIn(['PRODUCTION_OPERATE'], 'PRODUCTION_OPERATE', STORE)).toBe(false);
  });
});

function aJob(change: Partial<PrintJob>): PrintJob {
  return { id: 'j', printerId: 'p', status: 'FAILED', sourceType: 'BATCH', kind: 'ORIGINAL', attempts: 1, createdAt: 'x', ...change };
}

describe('what may be done with a job', () => {
  it('retries an unresolved FAILED or UNKNOWN job, never a drawer kick', () => {
    expect(canRetry(aJob({ status: 'FAILED' }))).toBe(true);
    expect(canRetry(aJob({ status: 'UNKNOWN' }))).toBe(true);
    for (const status of ['QUEUED', 'PROCESSING', 'SENT', 'RETRY_WAIT'] as const) expect(canRetry(aJob({ status }))).toBe(false);
    expect(canRetry(aJob({ status: 'FAILED', resolution: 'RETRIED' }))).toBe(false);
    expect(canRetry(aJob({ status: 'FAILED', sourceType: 'DRAWER_KICK' }))).toBe(false);
  });

  it('acknowledges only an unresolved UNKNOWN job', () => {
    expect(canAcknowledge(aJob({ status: 'UNKNOWN' }))).toBe(true);
    expect(canAcknowledge(aJob({ status: 'FAILED' }))).toBe(false);
    expect(canAcknowledge(aJob({ status: 'UNKNOWN', resolution: 'CONFIRMED_PRINTED' }))).toBe(false);
  });
});

describe('parsing the answers', () => {
  it('accepts what the backend sends, nulls included, and refuses a printer without a mode', () => {
    const printer = { id: 'p', code: 'C', name: 'N', host: 'h', port: 9100, codePage: 'CP860', active: true, mode: 'NORMAL',
      version: 0, redirectPrinterId: null, lastStatus: null };
    expect(parsePrinters([printer])[0]).toMatchObject({ mode: 'NORMAL', redirectPrinterId: null });
    expect(() => parsePrinters([{ ...printer, mode: undefined }])).toThrow();
    expect(() => parseJobs([{ ...aJob({}), status: 'LOST' }])).toThrow();
  });
});

describe('jobTitle', () => {
  it('names the source in Portuguese, with its destination when there is one', () => {
    expect(jobTitle('BATCH', 'COZINHA')).toBe('Pedido · COZINHA');
    expect(jobTitle('SUBTOTAL', null)).toBe('Consulta de mesa');
    expect(jobTitle('NEW_SOURCE', undefined)).toBe('NEW_SOURCE');
  });
});

const values: PrinterFormValues = {
  code: 'COZ',
  name: 'Cozinha',
  host: '10.0.0.5',
  port: '9101',
  codePage: 'CP850',
  preset: 'GENERIC_58',
  active: false,
};

describe('printer requests', () => {
  it("creates with the chosen preset's settings, the port as a number, and no preset when none matches", () => {
    const presets = [{ name: 'GENERIC_58', codePage: 'CP437' as const, profile: { paperWidthMm: 58, columns: 32 } }];
    expect(createRequest(STORE, values, presets)).toEqual({
      storeId: STORE, code: 'COZ', name: 'Cozinha', host: '10.0.0.5', port: 9101, codePage: 'CP850', paperWidthMm: 58, columns: 32,
    });
    expect(createRequest(STORE, { ...values, preset: '' }, presets)).toEqual({
      storeId: STORE, code: 'COZ', name: 'Cozinha', host: '10.0.0.5', port: 9101, codePage: 'CP850',
    });
  });

  it('updates at the given version with the active flag and no preset', () => {
    expect(updateRequest(values, 7)).toEqual({
      code: 'COZ', name: 'Cozinha', host: '10.0.0.5', port: 9101, codePage: 'CP850', active: false, version: 7,
    });
  });
});

describe('fieldErrors', () => {
  const FIELDS = ['code', 'name'] as const;

  it("puts a 400's violations on their fields and the rest together", () => {
    const error = new ApiError({
      status: 400,
      code: 'VALIDATION_FAILED',
      title: 'Bad',
      errors: [
        { field: 'name', message: 'too long' },
        { field: 'columns', message: 'out of range' },
      ],
    });
    expect(fieldErrors(error, FIELDS)).toEqual({ fields: { name: 'too long' }, others: ['out of range'] });
  });

  it('puts a code that names a field on it, in Portuguese', () => {
    const error = new ApiError({ status: 409, code: 'PRINTER_CODE_TAKEN', title: 'Conflict' });
    expect(fieldErrors(error, FIELDS, { PRINTER_CODE_TAKEN: 'code' })).toEqual({
      fields: { code: 'Já existe uma impressora com este código nesta loja.' },
      others: [],
    });
  });

  it('leaves any other answer to the form', () => {
    expect(fieldErrors(new ApiError({ status: 400, code: 'BAD', title: 'Bad' }), FIELDS)).toBeUndefined();
    expect(fieldErrors(new ApiError({ status: 409, code: 'VERSION_CONFLICT', title: 'C' }), FIELDS)).toBeUndefined();
    expect(fieldErrors(new Error('boom'), FIELDS)).toBeUndefined();
  });
});

describe('routesSchema', () => {
  it('sends destinations in capitals and refuses one twice, ignoring case and spaces', () => {
    expect(routesSchema.parse({ routes: [{ destinationCode: ' bar ', printerId: 'p' }] })).toEqual({
      routes: [{ destinationCode: 'BAR', printerId: 'p' }],
    });
    const twice = routesSchema.safeParse({
      routes: [
        { destinationCode: 'BAR', printerId: 'p' },
        { destinationCode: ' bar', printerId: 'q' },
      ],
    });
    expect(twice.success).toBe(false);
    expect(twice.error?.issues.map((issue) => [issue.path.join('.'), issue.message])).toEqual([
      ['routes.1.destinationCode', 'Este destino já tem rota.'],
    ]);
  });
});
