import type { RouteObject } from 'react-router';
import { AppShell } from '../shell/app-shell';
import { NotFoundPage } from './not-found-page';
import { OverviewPage } from './overview-page';
import { TerminalsPage } from './terminals-page';

/** FC §4: `/` and one route per §5 area as it arrives; the store is `?storeId` on each. */
export const routes: RouteObject[] = [
  {
    element: <AppShell />,
    children: [
      { index: true, element: <OverviewPage /> },
      { path: 'organization/terminals', element: <TerminalsPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
