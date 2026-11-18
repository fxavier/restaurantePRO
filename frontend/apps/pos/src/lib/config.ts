/** Build-time environment (FC §3). An empty issuer leaves an enrolled terminal "Sem ligação": it fails closed. */
export const DEVICES_ISSUER = import.meta.env.VITE_DEVICES_ISSUER ?? '';
