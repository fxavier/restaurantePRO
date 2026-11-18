import { Navigate, type RouteObject } from 'react-router';
import { EnrolPage } from './enrol-page';
import { LoginPage } from './login-page';
import { RequireDevice } from './require-device';

/** FC §4: `/enrol` without a device key; every other route needs one (and, from issue 09, a POS session). */
export const routes: RouteObject[] = [
  { path: '/enrol', element: <EnrolPage /> },
  {
    element: <RequireDevice />,
    children: [
      { path: '/login', element: <LoginPage /> },
      { path: '*', element: <Navigate to="/login" replace /> },
    ],
  },
];
