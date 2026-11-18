import type { TenantChoice } from '../types';

/** A company that cannot be worked in carries a label, never colour alone (§8.4). */
export function tenantStatusLabel(status: TenantChoice['status']): string | undefined {
  switch (status) {
    case 'SUSPENDED':
      return 'Suspensa';
    case 'CLOSED':
      return 'Encerrada';
    case 'PROVISIONING':
      return 'Em preparação';
    default:
      return undefined;
  }
}
