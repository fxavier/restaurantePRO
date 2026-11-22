import type { PermissionView } from './types';

/** One block of the permission matrix: an area's permissions, in the catalogue's order. */
export type PermissionArea = { area: string; label: string; permissions: PermissionView[] };

/** The Portuguese name of each `PermissionView.area` (the first word of the permission's name). */
const AREA_LABELS: Readonly<Record<string, string>> = {
  COMPANY: 'Empresa',
  STORE: 'Lojas',
  TERMINAL: 'Terminais',
  USER: 'Contas do BackOffice',
  ROLE: 'Funções',
  EMPLOYEE: 'Empregados',
  DINING: 'Sala',
  TABLE: 'Mesas',
  CATALOG: 'Catálogo',
  ORDER: 'Pedidos',
  PRODUCTION: 'Produção',
  BILL: 'Divisão da conta',
  PAYMENT: 'Pagamentos',
  CASH: 'Caixa',
  FISCAL: 'Documentos fiscais',
  RECONCILIATION: 'Reconciliação',
  CUSTOMER: 'Clientes',
  CREDIT: 'Crédito',
  PROMOTION: 'Promoções',
  LOYALTY: 'Fidelização',
  RESERVATION: 'Reservas',
  DELIVERY: 'Entregas',
  INVENTORY: 'Stocks',
  RECIPE: 'Fichas técnicas',
  PURCHASE: 'Compras',
  GOODS: 'Receção de mercadoria',
  REPORT: 'Relatórios',
  AUDIT: 'Auditoria',
  SYSTEM: 'Sistema',
  SUPPORT: 'Acessos de suporte',
};

/** The catalogue grouped by area, areas in the order the server first lists them; an unknown area shows as its code. */
export function permissionAreas(catalogue: readonly PermissionView[]): PermissionArea[] {
  const byArea = new Map<string, PermissionView[]>();
  for (const permission of catalogue) byArea.set(permission.area, [...(byArea.get(permission.area) ?? []), permission]);
  return [...byArea].map(([area, permissions]) => ({ area, label: AREA_LABELS[area] ?? area, permissions }));
}
