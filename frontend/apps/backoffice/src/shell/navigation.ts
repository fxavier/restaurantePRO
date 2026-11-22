import {
  Armchair,
  BookOpen,
  Boxes,
  Contact,
  LayoutDashboard,
  Landmark,
  MapPinned,
  MonitorSmartphone,
  Printer,
  ReceiptText,
  Store,
  Truck,
  UserCog,
  type LucideIcon,
} from 'lucide-react';

export type Area = { label: string; to: string; icon: LucideIcon; end?: boolean; permission?: string };
export type AreaGroup = { label?: string; areas: Area[] };

/**
 * The §5 areas in the sidebar, in Stitch's order; each shows only with its permission (story 8). Names are the
 * backend's `access.Permission` constants (backend/src/main/java/com/xavier/backend/access/Permission.java), each the
 * one the area's list endpoint requires, so an area shows exactly when its first read can succeed.
 */
export const areaGroups: AreaGroup[] = [
  { areas: [{ label: 'Visão geral', to: '/', icon: LayoutDashboard, end: true }] },
  {
    label: 'Organização',
    areas: [
      // STORE_MANAGE: StoreService's list and edits.
      { label: 'Lojas', to: '/organization/stores', icon: Store, permission: 'STORE_MANAGE' },
      { label: 'Terminais', to: '/organization/terminals', icon: MonitorSmartphone, permission: 'TERMINAL_MANAGE' },
      // DINING_MANAGE: RoomAreaService's areas and tables, DiningSettingsService.
      { label: 'Salas & Mesas', to: '/organization/dining', icon: Armchair, permission: 'DINING_MANAGE' },
    ],
  },
  {
    areas: [
      // CATALOG_MANAGE: ItemService and FamilyService read and write with it (CATALOG_VIEW gates only exclusions).
      { label: 'Catálogo', to: '/catalog', icon: BookOpen, permission: 'CATALOG_MANAGE' },
      // PRODUCTION_MANAGE: "printers, routes, modes, test print" (PrinterService).
      { label: 'Produção & Impressoras', to: '/production', icon: Printer, permission: 'PRODUCTION_MANAGE' },
      // INVENTORY_VIEW: "stock, movements, inventory reports and status (read)".
      { label: 'Stocks', to: '/inventory', icon: Boxes, permission: 'INVENTORY_VIEW' },
      // CUSTOMER_VIEW: "search and view customers, their statement and aging" (company-wide).
      { label: 'Clientes', to: '/customers', icon: Contact, permission: 'CUSTOMER_VIEW' },
      // ORDER_VIEW: BackOfficeOrderQueryService's search and detail (GET /api/v1/backoffice/orders).
      { label: 'Pedidos', to: '/orders', icon: ReceiptText, permission: 'ORDER_VIEW' },
      // CASH_VIEW: "sessions, days and closings (read)" (CashClosingService, CashDayService).
      { label: 'Financeiro', to: '/finance', icon: Landmark, permission: 'CASH_VIEW' },
      // EMPLOYEE_MANAGE: EmployeeService's list and edits.
      { label: 'Utilizadores & Empregados', to: '/people', icon: UserCog, permission: 'EMPLOYEE_MANAGE' },
    ],
  },
  {
    label: 'Delivery',
    areas: [
      // DELIVERY_ZONE_MANAGE: DeliveryZoneService's BackOffice list and edits.
      { label: 'Zonas', to: '/delivery/zones', icon: MapPinned, permission: 'DELIVERY_ZONE_MANAGE' },
      // ORDER_VIEW: DeliveryQueryService's history (GET /api/v1/backoffice/deliveries).
      { label: 'Entregas', to: '/delivery/report', icon: Truck, permission: 'ORDER_VIEW' },
    ],
  },
];

/** The groups and areas a user with these permissions may see; a group with no visible area disappears. */
export function visibleAreas(permissions: readonly string[]): AreaGroup[] {
  return areaGroups
    .map((group) => ({
      ...group,
      areas: group.areas.filter((area) => !area.permission || holds(permissions, area.permission)),
    }))
    .filter((group) => group.areas.length > 0);
}

/**
 * `SessionView.permissions` are the backend's authorities: `PERM_<NAME>` company-wide or `PERM_<NAME>@<storeId>` in
 * one store. An area shows with the permission anywhere; the API decides per store.
 */
function holds(permissions: readonly string[], name: string): boolean {
  const authority = `PERM_${name}`;
  return permissions.some((permission) => permission === authority || permission.startsWith(`${authority}@`));
}
