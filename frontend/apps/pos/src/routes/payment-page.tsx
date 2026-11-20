import { ConnectionBanner } from '@restaurantpro/ui';
import { useParams } from 'react-router';
import { PaymentScreen } from '../features/settlement/components/payment-screen';
import { useCommandsBlocked } from '../lib/live/use-connection';
import { StatusBar } from './status-bar';

/** `/orders/:orderId/payment` (§4, Stitch "Conta e Pagamento"): an order's bill, split and payment. */
export function PaymentPage() {
  const { orderId = '' } = useParams();
  const blocked = useCommandsBlocked();
  return (
    <div className="flex min-h-screen flex-col bg-background">
      <StatusBar />
      {blocked && <ConnectionBanner />}
      <PaymentScreen orderId={orderId} blocked={blocked} />
    </div>
  );
}
