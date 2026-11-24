import { CustomersScreen } from '../features/customers';
import { useSession } from '../features/identity';
import { useStoreSelection } from '../features/organization';

/** `/customers` (§5, Stitch "Clientes, Crédito & Conta Corrente"): customers, their credit, statement and loyalty. */
export function CustomersPage() {
  const { data: session } = useSession();
  const { storeId } = useStoreSelection();
  return (
    <>
      <p className="w-fit rounded-full bg-success-tint px-2.5 py-0.5 text-xs font-semibold tracking-wide text-success-text uppercase">
        Clientes e crédito
      </p>
      <h1 className="mt-2 text-2xl font-semibold">Clientes, Crédito & Conta Corrente</h1>
      <p className="mt-1 text-muted-foreground">
        Os clientes, o limite de crédito, o extrato da conta corrente, as dívidas por antiguidade e a fidelização.
      </p>
      <CustomersScreen authorities={session?.permissions ?? []} storeId={storeId} />
    </>
  );
}
