import { Card, CardContent } from '@restaurantpro/ui';
import { DebtsTable } from './debts-table';

/** Stitch "Clientes, Crédito & Conta Corrente": the customers on the left, the selected one on the right. */
export function CustomersScreen() {
  return (
    <div className="mt-6 grid gap-6">
      <Card>
        <CardContent>
          <DebtsTable onSelect={() => undefined} />
        </CardContent>
      </Card>
    </div>
  );
}
