import { useCustomerSelection } from '../hooks/use-customer-selection';
import { CustomerDetail } from './customer-detail';
import { CustomerList } from './customer-list';

/** Stitch "Clientes, Crédito & Conta Corrente": the customers on the left, the selected one on the right. */
export function CustomersScreen() {
  const { customerId, selectCustomer } = useCustomerSelection();
  return (
    <div className="mt-6 grid items-start gap-6 lg:grid-cols-2">
      <CustomerList onSelect={selectCustomer} />
      {customerId && <CustomerDetail key={customerId} customerId={customerId} />}
    </div>
  );
}
