/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** The Keycloak devices realm's issuer, e.g. `https://id.example.com/realms/restaurantpro-devices` (FC §3). */
  readonly VITE_DEVICES_ISSUER?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
