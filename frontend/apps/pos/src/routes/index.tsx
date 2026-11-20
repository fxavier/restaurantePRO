import { Navigate, type RouteObject } from 'react-router';
import { CashPage } from './cash-page';
import { DeliveryPage } from './delivery-page';
import { EnrolPage } from './enrol-page';
import { LoginPage } from './login-page';
import { OrderPage } from './order-page';
import { PaymentPage } from './payment-page';
import { RequireDevice } from './require-device';
import { RequireSession } from './require-session';
import { ReservationsPage } from './reservations-page';
import { TablePage } from './table-page';
import { TablesPage } from './tables-page';

/** FC §4: `/enrol` without a device key; every other route needs one, and every route but `/login` a POS session. */
export const routes: RouteObject[] = [
  { path: '/enrol', element: <EnrolPage /> },
  {
    element: <RequireDevice />,
    children: [
      { path: '/login', element: <LoginPage /> },
      {
        element: <RequireSession />,
        children: [
          { path: '/tables', element: <TablesPage /> },
          { path: '/tables/:tableId', element: <TablePage /> },
          { path: '/orders/:orderId', element: <OrderPage /> },
          { path: '/orders/:orderId/payment', element: <PaymentPage /> },
          { path: '/delivery', element: <DeliveryPage /> },
          { path: '/reservations', element: <ReservationsPage /> },
          { path: '/cash', element: <CashPage /> },
          { path: '*', element: <Navigate to="/tables" replace /> },
        ],
      },
    ],
  },
];
