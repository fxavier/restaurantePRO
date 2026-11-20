import { CustomersScreen } from '../features/customers';

/** `/customers` (§5, Stitch "Clientes, Crédito & Conta Corrente"): customers, their credit, statement and loyalty. */
export function CustomersPage() {
  return (
    <>
      <p className="w-fit rounded-sm bg-primary/15 px-2 py-0.5 text-xs font-semibold tracking-wide text-primary uppercase">
        Clientes e crédito
      </p>
      <h1 className="mt-2 text-2xl font-semibold">Clientes, Crédito & Conta Corrente</h1>
      <p className="mt-1 text-muted-foreground">
        Os clientes, o limite de crédito, o extrato da conta corrente, as dívidas por antiguidade e a fidelização.
      </p>
      <CustomersScreen />
    </>
  );
}
