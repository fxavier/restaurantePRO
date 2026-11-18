import type { RouteObject } from 'react-router';
import { PlaceholderPage } from './placeholder-page';

export const routes: RouteObject[] = [{ path: '*', element: <PlaceholderPage /> }];
