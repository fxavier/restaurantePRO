import type { components } from '@restaurantpro/api-client';
import type { MoneyValue } from '@restaurantpro/ui';

type Schemas = components['schemas'];
type SettlementView = Schemas['com.xavier.backend.settlement.model.dto.OrderSettlementView'];

/**
 * An order's bill as settlement has it (`GET /orders/{id}/settlement`): every amount and state is the server's, read
 * again after every command; nothing here is computed from them.
 */
export type Settlement = {
  orderId: string;
  lifecycle: string;
  orderVersion: number;
  due: MoneyValue;
  paid: MoneyValue;
  balance: MoneyValue;
};

/** The contract marks no field of the view required; a bill without these cannot be shown or acted on. */
export function toSettlement(view: SettlementView | undefined): Settlement {
  if (!view?.orderId || !view.lifecycle || view.orderVersion === undefined || !view.due || !view.paid || !view.balance) {
    throw new Error('Incomplete settlement');
  }
  return {
    orderId: view.orderId,
    lifecycle: view.lifecycle,
    orderVersion: view.orderVersion,
    due: view.due,
    paid: view.paid,
    balance: view.balance,
  };
}
