import type { components } from '@restaurantpro/api-client';

type Schemas = components['schemas'];
type Dto<Name extends string> = Schemas[`com.xavier.backend.production.model.dto.${Name}` & keyof Schemas];

export type PrinterView = Dto<'PrinterView'>;
export type PrinterCreateRequest = Dto<'PrinterCreateRequest'>;
export type PrinterUpdateRequest = Dto<'PrinterUpdateRequest'>;
export type PrinterModeRequest = Dto<'PrinterModeRequest'>;
export type PrinterPresetView = Dto<'PrinterPresetView'>;
export type PrintJobView = Dto<'PrintJobView'>;
export type TicketView = Dto<'TicketView'>;
export type DestinationRouteView = Dto<'DestinationRouteView'>;
export type TerminalPrinterView = Dto<'TerminalPrinterView'>;

export type PrinterMode = PrinterModeRequest['mode'];
export type PrintJobStatus = NonNullable<PrintJobView['status']>;
export type CodePage = PrinterCreateRequest['codePage'];
