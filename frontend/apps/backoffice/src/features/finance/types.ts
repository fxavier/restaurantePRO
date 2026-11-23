import type { components, paths } from '@restaurantpro/api-client';

type Schemas = components['schemas'];
export type Money = Schemas['com.xavier.backend.shared.Money'];
export type SalesRow = Schemas['com.xavier.backend.reporting.model.dto.SalesRow'];
export type ReceiptsRow = Schemas['com.xavier.backend.reporting.model.dto.ReceiptsRow'];
export type SalesReport = Schemas['com.xavier.backend.reporting.model.dto.ReportViewCom.xavier.backend.reporting.model.dto.SalesRow'];
export type ReceiptsReport =
  Schemas['com.xavier.backend.reporting.model.dto.ReportViewCom.xavier.backend.reporting.model.dto.ReceiptsRow'];
/** The closing states the list filters on (spec §8.3). */
export type ClosingStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED';
export type ClosingView = Schemas['com.xavier.backend.cash.model.dto.ClosingView'];
export type ClosingLineView = Schemas['com.xavier.backend.cash.model.dto.ClosingLineView'];
/** The cash session states the list filters on (spec §8.3), as the contract's `status` query parameter names them. */
export type CashSessionStatus = NonNullable<
  paths['/api/v1/backoffice/cash/sessions']['get']['parameters']['query']['status']
>;
export type SessionRow = Schemas['com.xavier.backend.cash.model.dto.SessionRow'];
export type EmployeeSummary = Schemas['com.xavier.backend.identity.model.dto.EmployeeSummary'];
export type DayView =Schemas['com.xavier.backend.cash.DayView'];
export type RegisterSummary = Schemas['com.xavier.backend.cash.model.dto.RegisterSummary'];
export type ReconciliationLine = Schemas['com.xavier.backend.cash.model.dto.ReconciliationLine'];
export type InDoubtAttemptView = Schemas['com.xavier.backend.settlement.reconciliation.model.dto.InDoubtAttemptView'];
export type SeriesView = Schemas['com.xavier.backend.fiscal.model.dto.SeriesView'];
export type DocumentSummary = Schemas['com.xavier.backend.fiscal.model.dto.DocumentSummary'];
export type PaymentMethodView = Schemas['com.xavier.backend.settlement.model.dto.PaymentMethodView'];
export type AuditEventView = Schemas['com.xavier.backend.audit.model.dto.AuditEventView'];
export type StoreSummary = Schemas['com.xavier.backend.organization.model.dto.StoreSummary'];
export type SeriesCreateRequest = Schemas['com.xavier.backend.fiscal.model.dto.SeriesCreateRequest'];
export type PaymentMethodCreateRequest = Schemas['com.xavier.backend.settlement.model.dto.PaymentMethodCreateRequest'];
export type PaymentMethodUpdateRequest = Schemas['com.xavier.backend.settlement.model.dto.PaymentMethodUpdateRequest'];
