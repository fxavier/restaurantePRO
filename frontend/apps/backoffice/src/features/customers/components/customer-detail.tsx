import { Badge, Card, CardContent, CardHeader, DataState, Tabs, TabsContent, TabsList, TabsTrigger } from '@restaurantpro/ui';
import { Mail } from 'lucide-react';
import { useId } from 'react';
import { useCustomer } from '../hooks/use-customer-queries';
import type { Allowed } from '../permissions';
import type { CustomerDetails } from '../types';
import { CreditTab } from './credit-tab';
import { CustomerDataTab } from './customer-data-tab';
import { LoyaltyTab } from './loyalty-tab';
import { QueryFailure } from './query-failure';

/** The right pane (Stitch's customer card): who the customer is, then one tab per concern. */
export function CustomerDetail({
  customerId,
  allowed,
  storeId,
}: {
  customerId: string;
  allowed: Allowed;
  storeId: string | undefined;
}) {
  const customer = useCustomer(customerId);
  const titleId = useId();

  if (!customer.data) {
    return (
      <Card role="region" aria-label="Cliente">
        <CardContent>
          {customer.isError ? (
            <QueryFailure error={customer.error} onRetry={() => void customer.refetch()} notFound="Cliente não encontrado." />
          ) : (
            <DataState state="loading">{null}</DataState>
          )}
        </CardContent>
      </Card>
    );
  }

  const data = customer.data;
  return (
    <Card role="region" aria-labelledby={titleId} className="gap-4">
      <CardHeader>
        <CustomerHeading customer={data} titleId={titleId} />
      </CardHeader>
      <CardContent>
        <Tabs defaultValue="data">
          <TabsList className="flex-wrap">
            <TabsTrigger value="data">Dados</TabsTrigger>
            <TabsTrigger value="contacts">Moradas & Contactos</TabsTrigger>
            <TabsTrigger value="credit">Crédito</TabsTrigger>
            {allowed.LOYALTY_VIEW && <TabsTrigger value="loyalty">Fidelização</TabsTrigger>}
          </TabsList>
          <TabsContent value="data" className="pt-4">
            <CustomerDataTab customer={data} canManage={allowed.CUSTOMER_MANAGE} />
          </TabsContent>
          <TabsContent value="contacts" className="pt-4" />
          <TabsContent value="credit" className="pt-4">
            <CreditTab customerId={customerId} canManage={allowed.CREDIT_LIMIT_MANAGE} />
          </TabsContent>
          {allowed.LOYALTY_VIEW && (
            <TabsContent value="loyalty" className="pt-4">
              <LoyaltyTab customerId={customerId} allowed={allowed} storeId={storeId} />
            </TabsContent>
          )}
        </Tabs>
      </CardContent>
    </Card>
  );
}

function CustomerHeading({ customer, titleId }: { customer: CustomerDetails; titleId: string }) {
  return (
    <div className="flex flex-col gap-1">
      <div className="flex flex-wrap items-center gap-2">
        <h2 id={titleId} className="text-xl font-semibold">
          {customer.name}
        </h2>
        {customer.active === false && <Badge variant="secondary">Inativo</Badge>}
      </div>
      <p className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-muted-foreground">
        {customer.taxId && <span className="font-mono">NIF {customer.taxId}</span>}
        {customer.email && (
          <span className="inline-flex items-center gap-1 text-primary-hover">
            <Mail aria-hidden="true" className="size-4" />
            {customer.email}
          </span>
        )}
      </p>
    </div>
  );
}
