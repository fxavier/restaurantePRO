import type { components } from '@restaurantpro/api-client';
import type { Employee } from '../../lib/auth/pos-session';

type Schemas = components['schemas'];
type ProductionAlertsView = Schemas['com.xavier.backend.production.model.dto.ProductionAlertsView'];
type SettlementAlertsView = Schemas['com.xavier.backend.settlement.model.dto.SettlementAlertsView'];

/** The print queue's and the kitchen display's warnings for the session's store (production). */
export type ProductionAlerts = { pendingPrintJobs: number; delayedKitchenRounds: number; kitchenDelayMinutes: number };

/** The payments in doubt of the session's store (settlement), and the order whose payment screen resolves the oldest. */
export type SettlementAlerts = { paymentsToConfirm: number; oldestOrderId?: string };

type Holder = Pick<Employee, 'permissions' | 'storeId'> | undefined;
const holds = (employee: Holder, permission: string) =>
  !!employee?.permissions.includes(`PERM_${permission}@${employee.storeId}`);

/** Who may read the print queue and the kitchen display (PRODUCTION_VIEW) sees their alerts; for showing only. */
export const maySeeProductionAlerts = (employee: Holder) => holds(employee, 'PRODUCTION_VIEW');

/** Who collects payments (and sees them on the bill) or resolves them sees the ones in doubt; for showing only. */
export const maySeePaymentAlerts = (employee: Holder) =>
  holds(employee, 'PAYMENT_COLLECT') || holds(employee, 'PAYMENT_RESOLVE');

/** The contract marks every count required (FE-F28); only a missing answer is an error here. */
export function toProductionAlerts(view: ProductionAlertsView | undefined): ProductionAlerts {
  if (!view) throw new Error('Incomplete production alerts');
  return view;
}

export function toSettlementAlerts(view: SettlementAlertsView | undefined): SettlementAlerts {
  if (!view) throw new Error('Incomplete settlement alerts');
  return { paymentsToConfirm: view.paymentsToConfirm, oldestOrderId: view.oldestOrderId ?? undefined };
}

/** What follows the count: "1 impressão pendente", "3 impressões pendentes". */
export const pendingPrintsWords = (count: number) => (count === 1 ? 'impressão pendente' : 'impressões pendentes');

export const paymentsToConfirmLabel = (count: number) =>
  count === 1 ? '1 pagamento por confirmar' : `${count} pagamentos por confirmar`;

export const lateRoundsLabel = (count: number, minutes: number) =>
  `Cozinha: ${count} ${count === 1 ? 'ronda' : 'rondas'} > ${minutes} min`;
