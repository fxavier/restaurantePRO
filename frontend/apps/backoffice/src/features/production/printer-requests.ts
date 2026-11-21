import type { PrinterFormValues } from './schemas';
import type { PrinterCreateRequest, PrinterPresetView, PrinterUpdateRequest } from './types';

/** A new printer: the form's fields and, when a preset was chosen, its settings (D154: presets only fill the form). */
export function createRequest(storeId: string, values: PrinterFormValues, presets: PrinterPresetView[]): PrinterCreateRequest {
  const profile = presets.find((preset) => preset.name === values.preset)?.profile ?? {};
  const { code, name, host, port, codePage } = values;
  return { storeId, code, name, host, port: Number(port), codePage, ...profile };
}

/** A changed printer at the version the form was opened on; the settings it does not send stay as they are. */
export function updateRequest(values: PrinterFormValues, version: number): PrinterUpdateRequest {
  const { code, name, host, port, codePage, active } = values;
  return { code, name, host, port: Number(port), codePage, active, version };
}
