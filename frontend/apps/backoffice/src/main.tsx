import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter } from 'react-router';
import { App } from './app';
import './index.css';
import { createBackOfficeClient } from './lib/api';
import { createQueryClient } from './lib/query-client';
import { routes } from './routes';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App router={createBrowserRouter(routes)} api={createBackOfficeClient()} queryClient={createQueryClient()} />
  </StrictMode>,
);
