import type { components } from '@restaurantpro/api-client';

type Schemas = components['schemas'];
export type Money = Schemas['com.xavier.backend.shared.Money'];
export type SalesRow = Schemas['com.xavier.backend.reporting.model.dto.SalesRow'];
export type ReceiptsRow = Schemas['com.xavier.backend.reporting.model.dto.ReceiptsRow'];
export type SalesReport = Schemas['com.xavier.backend.reporting.model.dto.ReportViewCom.xavier.backend.reporting.model.dto.SalesRow'];
export type ReceiptsReport =
  Schemas['com.xavier.backend.reporting.model.dto.ReportViewCom.xavier.backend.reporting.model.dto.ReceiptsRow'];
export type ClosingView = Schemas['com.xavier.backend.cash.model.dto.ClosingView'];
export type ClosingLineView = Schemas['com.xavier.backend.cash.model.dto.ClosingLineView'];
export type DayView = Schemas['com.xavier.backend.cash.DayView'];
export type RegisterSummary = Schemas['com.xavier.backend.cash.model.dto.RegisterSummary'];
export type ReconciliationLine = Schemas['com.xavier.backend.cash.model.dto.ReconciliationLine'];
export type InDoubtAttemptView = Schemas['com.xavier.backend.settlement.reconciliation.model.dto.InDoubtAttemptView'];
export type SeriesView = Schemas['com.xavier.backend.fiscal.model.dto.SeriesView'];
export type DocumentSummary = Schemas['com.xavier.backend.fiscal.model.dto.DocumentSummary'];
export type PaymentMethodView = Schemas['com.xavier.backend.settlement.model.dto.PaymentMethodView'];
export type AuditEventView = Schemas['com.xavier.backend.audit.model.dto.AuditEventView'];
export type StoreSummary = Schemas['com.xavier.backend.organization.model.dto.StoreSummary'];
