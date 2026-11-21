import { useCustomerSelection } from '../hooks/use-customer-selection';
import { allowedFrom } from '../permissions';
import { CustomerDetail } from './customer-detail';
import { CustomerList } from './customer-list';
import { NewCustomerDialog } from './new-customer-dialog';

type CustomersScreenProps = {
  /** The session's `permissions`: which controls to show (the backend enforces). */
  authorities: readonly string[];
  /** The store picked in the top bar; a loyalty adjustment is made in its name. */
  storeId: string | undefined;
};

/** Stitch "Clientes, Crédito & Conta Corrente": the customers on the left, the selected one on the right. */
export function CustomersScreen({ authorities, storeId }: CustomersScreenProps) {
  const { customerId, selectCustomer } = useCustomerSelection();
  const allowed = allowedFrom(authorities);
  return (
    <>
      {allowed.CUSTOMER_MANAGE && (
        <div className="mt-4 flex flex-wrap gap-2">
          <NewCustomerDialog onCreated={selectCustomer} />
        </div>
      )}
      <div className="mt-6 grid grid-cols-1 items-start gap-6 lg:grid-cols-2">
        <CustomerList onSelect={selectCustomer} />
        {customerId && <CustomerDetail key={customerId} customerId={customerId} allowed={allowed} storeId={storeId} />}
      </div>
    </>
  );
}
