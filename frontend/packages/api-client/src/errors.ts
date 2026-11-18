/** A field violation of a 400 answer (backend `GlobalExceptionHandler.FieldViolation`). */
export type FieldError = { field: string; message: string };

type ApiErrorFields = {
  status: number;
  code: string;
  title: string;
  detail?: string;
  errors?: FieldError[];
  acrValues?: string;
  maxAge?: number;
};

/**
 * The one error every API call throws (FC §7, §9). `status` 0 with `code` NETWORK means no answer arrived: the outcome
 * is unknown, because the connection may have dropped after the request was sent, so a command may have happened.
 * Retry it with the same Idempotency-Key, never a new one (FC §8, spec §7). Otherwise the fields come from the
 * problem+json body, and `code` falls back to `HTTP_<status>` when the body has none.
 * Language-neutral on purpose: each app maps `code` to its Portuguese text.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly title: string;
  readonly detail?: string;
  readonly errors?: FieldError[];
  readonly acrValues?: string;
  readonly maxAge?: number;

  constructor(fields: ApiErrorFields, options?: ErrorOptions) {
    super(fields.detail ?? fields.title, options);
    this.name = 'ApiError';
    this.status = fields.status;
    this.code = fields.code;
    this.title = fields.title;
    this.detail = fields.detail;
    this.errors = fields.errors;
    this.acrValues = fields.acrValues;
    this.maxAge = fields.maxAge;
  }
}

export function networkError(cause: unknown): ApiError {
  return new ApiError({ status: 0, code: 'NETWORK', title: 'Network error' }, { cause });
}

/** Reads a non-2xx answer. The body is read as text first, so a proxy's HTML error page maps as cleanly as JSON. */
export async function errorFromResponse(response: Response): Promise<ApiError> {
  const body = parseObject(await response.text().catch(() => ''));
  const status = response.status;
  return new ApiError({
    status,
    code: stringField(body, 'code') ?? `HTTP_${status}`,
    title: stringField(body, 'title') ?? (response.statusText || `HTTP ${status}`),
    detail: stringField(body, 'detail'),
    errors: fieldErrors(body?.errors),
    acrValues: stringField(body, 'acrValues'),
    maxAge: typeof body?.maxAge === 'number' ? body.maxAge : undefined,
  });
}

function parseObject(text: string): Record<string, unknown> | undefined {
  try {
    const value: unknown = JSON.parse(text);
    return typeof value === 'object' && value !== null && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : undefined;
  } catch {
    return undefined;
  }
}

function stringField(body: Record<string, unknown> | undefined, name: string): string | undefined {
  const value = body?.[name];
  return typeof value === 'string' ? value : undefined;
}

function fieldErrors(value: unknown): FieldError[] | undefined {
  if (!Array.isArray(value)) return undefined;
  return value.filter(
    (item): item is FieldError =>
      typeof item === 'object' && item !== null && typeof item.field === 'string' && typeof item.message === 'string',
  );
}
