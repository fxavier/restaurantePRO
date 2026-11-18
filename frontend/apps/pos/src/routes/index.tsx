import { Navigate, type RouteObject } from 'react-router';
import { EnrolPage } from './enrol-page';
import { LoginPage } from './login-page';
import { OrderPage } from './order-page';
import { RequireDevice } from './require-device';
import { RequireSession } from './require-session';
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
          { path: '*', element: <Navigate to="/tables" replace /> },
        ],
      },
    ],
  },
];
