import type { RouteObject } from 'react-router';
import { AppShell } from '../shell/app-shell';
import { CatalogPage } from './catalog-page';
import { CustomersPage } from './customers-page';
import { DeliveryReportPage } from './delivery-report-page';
import { DeliveryZonesPage } from './delivery-zones-page';
import { DiningPage } from './dining-page';
import { FinancePage } from './finance-page';
import { InventoryPage } from './inventory-page';
import { NotFoundPage } from './not-found-page';
import { OverviewPage } from './overview-page';
import { PeoplePage } from './people-page';
import { ProductionPage } from './production-page';
import { StepUpResumePage } from './step-up-resume-page';
import { StoresPage } from './stores-page';
import { TerminalsPage } from './terminals-page';

/**
 * FC §4: `/`, one route per §5 area (the store is `?storeId` on each), and the step-up return. Each area is its own
 * page file, so a screen replaces only its own file (shell/navigation.ts names the sidebar's entries).
 */
export const routes: RouteObject[] = [
  {
    element: <AppShell />,
    children: [
      { index: true, element: <OverviewPage /> },
      { path: 'organization/stores', element: <StoresPage /> },
      { path: 'organization/terminals', element: <TerminalsPage /> },
      { path: 'organization/dining', element: <DiningPage /> },
      { path: 'catalog', element: <CatalogPage /> },
      { path: 'production', element: <ProductionPage /> },
      { path: 'inventory', element: <InventoryPage /> },
      { path: 'customers', element: <CustomersPage /> },
      { path: 'finance', element: <FinancePage /> },
      { path: 'people', element: <PeoplePage /> },
      { path: 'delivery/zones', element: <DeliveryZonesPage /> },
      { path: 'delivery/report', element: <DeliveryReportPage /> },
      { path: 'step-up/resume', element: <StepUpResumePage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
