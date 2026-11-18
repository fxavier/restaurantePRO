import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter } from 'react-router';
import './index.css';
import { PosApp } from './app';
import { createDeviceStore } from './lib/auth/device-store';
import { DEVICES_ISSUER } from './lib/config';
import { createPosServices } from './lib/services';
import { routes } from './routes';

const services = createPosServices({ issuer: DEVICES_ISSUER, devices: createDeviceStore() });

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <PosApp services={services} router={createBrowserRouter(routes)} />
  </StrictMode>,
);
