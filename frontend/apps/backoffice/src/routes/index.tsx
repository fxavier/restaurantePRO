import type { RouteObject } from 'react-router';
import { AppShell } from '../shell/app-shell';
import { NotFoundPage } from './not-found-page';
import { OverviewPage } from './overview-page';
import { StepUpResumePage } from './step-up-resume-page';
import { TerminalsPage } from './terminals-page';

/** FC §4: `/`, one route per §5 area as it arrives (the store is `?storeId` on each), and the step-up return. */
export const routes: RouteObject[] = [
  {
    element: <AppShell />,
    children: [
      { index: true, element: <OverviewPage /> },
      { path: 'organization/terminals', element: <TerminalsPage /> },
      { path: 'step-up/resume', element: <StepUpResumePage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
