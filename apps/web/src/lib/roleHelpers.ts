import type { AttendanceEventView, CurrencyBalanceView, Role } from '@proofly/api-client';

export type WorkspaceTabId =
  | 'shift'
  | 'team'
  | 'reviews'
  | 'payroll'
  | 'sites'
  | 'staff'
  | 'billing'
  | 'outbox'
  | 'audit'
  | 'overview';

export interface WorkspaceTabMeta {
  id: WorkspaceTabId;
  label: string;
  mobileLabel: string;
  group: 'PERSONAL' | 'OPERATIONS' | 'ADMINISTRATION';
  minRole: Role;
}

export const WORKSPACE_TABS: readonly WorkspaceTabMeta[] = [
  { id: 'shift', label: 'My Shift & Leave', mobileLabel: 'My Shift', group: 'PERSONAL', minRole: 'EMPLOYEE' },
  { id: 'team', label: 'Live Site Board', mobileLabel: 'Team Live', group: 'OPERATIONS', minRole: 'MANAGER' },
  { id: 'reviews', label: 'Review Queue', mobileLabel: 'Reviews', group: 'OPERATIONS', minRole: 'MANAGER' },
  { id: 'payroll', label: 'Payroll & CSV', mobileLabel: 'Payroll', group: 'OPERATIONS', minRole: 'MANAGER' },
  { id: 'sites', label: 'Sites & Geofences', mobileLabel: 'Sites', group: 'ADMINISTRATION', minRole: 'ADMIN' },
  { id: 'staff', label: 'Staff & Invites', mobileLabel: 'Staff', group: 'ADMINISTRATION', minRole: 'ADMIN' },
  { id: 'billing', label: 'Billing & Gateways', mobileLabel: 'Billing', group: 'ADMINISTRATION', minRole: 'ADMIN' },
  { id: 'outbox', label: 'Notification Outbox', mobileLabel: 'Outbox', group: 'ADMINISTRATION', minRole: 'ADMIN' },
  { id: 'audit', label: 'Audit Trail', mobileLabel: 'Audit', group: 'ADMINISTRATION', minRole: 'ADMIN' },
  { id: 'overview', label: 'Devices & Security', mobileLabel: 'Security', group: 'PERSONAL', minRole: 'EMPLOYEE' },
];

const ROLE_RANK: Record<Role, number> = {
  EMPLOYEE: 1,
  MANAGER: 2,
  ADMIN: 3,
  SUPER_ADMIN: 4,
};

export function hasRoleAtLeast(userRole: Role, requiredRole: Role): boolean {
  return ROLE_RANK[userRole] >= ROLE_RANK[requiredRole];
}

export function getVisibleWorkspaceTabs(userRole: Role): WorkspaceTabMeta[] {
  return WORKSPACE_TABS.filter((tab) => hasRoleAtLeast(userRole, tab.minRole));
}

export function resolveSafeWorkspaceTab(rawTab: string | null | undefined, userRole: Role): WorkspaceTabId {
  const allowed = getVisibleWorkspaceTabs(userRole);
  const match = allowed.find((t) => t.id === rawTab);
  return match ? match.id : 'shift';
}

export function getMobileBottomTabs(userRole: Role): WorkspaceTabMeta[] {
  const allowed = getVisibleWorkspaceTabs(userRole);
  const priorityOrder: WorkspaceTabId[] = hasRoleAtLeast(userRole, 'ADMIN')
    ? ['shift', 'team', 'reviews', 'sites', 'overview']
    : hasRoleAtLeast(userRole, 'MANAGER')
      ? ['shift', 'team', 'reviews', 'payroll', 'overview']
      : ['shift', 'overview'];

  return priorityOrder
    .map((id) => allowed.find((t) => t.id === id))
    .filter((t): t is WorkspaceTabMeta => Boolean(t));
}

export function canEditMemberRole(actorRole: Role, targetCurrentRole: Role, targetNextRole?: Role): boolean {
  if (actorRole === 'SUPER_ADMIN') {
    return true;
  }
  if (actorRole === 'ADMIN') {
    if (targetCurrentRole === 'SUPER_ADMIN') {
      return false;
    }
    if (targetNextRole && (targetNextRole === 'SUPER_ADMIN' || targetNextRole === 'ADMIN')) {
      return false;
    }
    return true;
  }
  return false;
}

export function deriveActiveOpenClockIn(events: AttendanceEventView[]): AttendanceEventView | null {
  if (!events || events.length === 0) return null;
  const sorted = [...events].sort(
    (a, b) => new Date(b.effectiveAt).getTime() - new Date(a.effectiveAt).getTime()
  );
  const pairedIds = new Set(
    sorted
      .map((e) => e.pairedClockInId)
      .filter((id): id is string => typeof id === 'string' && id.length > 0)
  );
  return (
    sorted.find(
      (ev) =>
        ev.eventType === 'CLOCK_IN' &&
        ev.geofenceStatus !== 'REJECTED' &&
        !pairedIds.has(ev.id)
    ) ?? null
  );
}

export function formatDurationMinutes(totalMinutes: number): string {
  const safe = Math.max(0, Math.round(totalMinutes));
  const hours = Math.floor(safe / 60);
  const mins = safe % 60;
  if (hours === 0) return `${mins}m`;
  return `${hours}h ${String(mins).padStart(2, '0')}m`;
}

export function formatDecimalHours(totalMinutes: number): string {
  return (Math.max(0, totalMinutes) / 60).toFixed(2);
}

export function resolvePreferredSettlementCurrency(
  subscriptionCurrency: string | null | undefined,
  urlCurrency: string | null | undefined
): 'NGN' | 'USD' | 'GBP' | 'KES' | 'GHS' {
  const supported = new Set(['NGN', 'USD', 'GBP', 'KES', 'GHS']);
  const fromUrl = urlCurrency?.trim().toUpperCase();
  if (fromUrl && supported.has(fromUrl)) {
    return fromUrl as 'NGN' | 'USD' | 'GBP' | 'KES' | 'GHS';
  }
  const fromSub = subscriptionCurrency?.trim().toUpperCase();
  if (fromSub && supported.has(fromSub)) {
    return fromSub as 'NGN' | 'USD' | 'GBP' | 'KES' | 'GHS';
  }
  return 'NGN';
}

export function partitionCurrencyBalances(balances: CurrencyBalanceView[]): CurrencyBalanceView[] {
  // Never convert across currencies; return sorted distinct currency ledger accounts as-is.
  return [...balances].sort((a, b) => a.currency.localeCompare(b.currency));
}
