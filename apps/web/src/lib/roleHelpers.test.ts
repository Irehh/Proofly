import test from 'node:test';
import assert from 'node:assert/strict';
import type { AttendanceEventView, CurrencyBalanceView } from '@proofly/api-client';
import {
  canEditMemberRole,
  deriveActiveOpenClockIn,
  formatDecimalHours,
  formatDurationMinutes,
  getMobileBottomTabs,
  getVisibleWorkspaceTabs,
  partitionCurrencyBalances,
  resolvePreferredSettlementCurrency,
  resolveSafeWorkspaceTab,
} from './roleHelpers';

test('getVisibleWorkspaceTabs enforces role boundaries while keeping My Shift accessible to all roles', () => {
  const employeeTabs = getVisibleWorkspaceTabs('EMPLOYEE').map((t) => t.id);
  assert.deepEqual(employeeTabs, ['shift', 'overview']);

  const managerTabs = getVisibleWorkspaceTabs('MANAGER').map((t) => t.id);
  assert.deepEqual(managerTabs, ['shift', 'team', 'reviews', 'payroll', 'overview']);

  const adminTabs = getVisibleWorkspaceTabs('ADMIN').map((t) => t.id);
  assert.equal(adminTabs.includes('billing'), true);
  assert.equal(adminTabs.includes('sites'), true);
  assert.equal(adminTabs.includes('staff'), true);
  assert.equal(adminTabs.includes('audit'), true);

  assert.equal(resolveSafeWorkspaceTab('billing', 'EMPLOYEE'), 'shift');
  assert.equal(resolveSafeWorkspaceTab('reviews', 'MANAGER'), 'reviews');
  assert.equal(resolveSafeWorkspaceTab('sites', 'MANAGER'), 'shift');
});

test('getMobileBottomTabs limits primary bottom bar tabs to <= 5 high-frequency actions', () => {
  assert.equal(getMobileBottomTabs('EMPLOYEE').length, 2);
  assert.equal(getMobileBottomTabs('MANAGER').length, 5);
  assert.equal(getMobileBottomTabs('SUPER_ADMIN').length, 5);
});

test('canEditMemberRole blocks ADMIN from elevating to ADMIN/SUPER_ADMIN or modifying SUPER_ADMIN', () => {
  assert.equal(canEditMemberRole('ADMIN', 'EMPLOYEE', 'MANAGER'), true);
  assert.equal(canEditMemberRole('ADMIN', 'EMPLOYEE', 'ADMIN'), false);
  assert.equal(canEditMemberRole('ADMIN', 'SUPER_ADMIN', 'EMPLOYEE'), false);
  assert.equal(canEditMemberRole('SUPER_ADMIN', 'ADMIN', 'SUPER_ADMIN'), true);
});

test('deriveActiveOpenClockIn identifies an unpaired non-rejected clock-in', () => {
  const baseEvent: AttendanceEventView = {
    id: 'ev-1',
    membershipId: 'm-1',
    staffName: 'Ada',
    siteId: 's-1',
    siteName: 'Apapa Gate',
    eventType: 'CLOCK_IN',
    occurredAt: '2026-11-15T08:00:00Z',
    effectiveAt: '2026-11-15T08:00:00Z',
    localDate: '2026-11-15',
    distanceMeters: 25,
    accuracyMeters: 12,
    geofenceStatus: 'VERIFIED',
    isLate: false,
    lateMinutes: 0,
    workedMinutes: 0,
    overtimeMinutes: 0,
  };

  assert.equal(deriveActiveOpenClockIn([baseEvent])?.id, 'ev-1');

  const closedEvent: AttendanceEventView = {
    ...baseEvent,
    id: 'ev-2',
    eventType: 'CLOCK_OUT',
    pairedClockInId: 'ev-1',
    occurredAt: '2026-11-15T17:00:00Z',
    effectiveAt: '2026-11-15T17:00:00Z',
    workedMinutes: 540,
    overtimeMinutes: 60,
  };

  assert.equal(deriveActiveOpenClockIn([baseEvent, closedEvent]), null);
});

test('duration formatters and zero-conversion currency balance helpers preserve exact units', () => {
  assert.equal(formatDurationMinutes(125), '2h 05m');
  assert.equal(formatDurationMinutes(42), '42m');
  assert.equal(formatDecimalHours(510), '8.50');

  assert.equal(resolvePreferredSettlementCurrency('USD', undefined), 'USD');
  assert.equal(resolvePreferredSettlementCurrency('USD', 'GBP'), 'GBP');
  assert.equal(resolvePreferredSettlementCurrency(null, null), 'NGN');

  const balances: CurrencyBalanceView[] = [
    { currency: 'USD', gateway: 'STRIPE', creditBalanceMinor: 25920 },
    { currency: 'NGN', gateway: 'PAYSTACK', creditBalanceMinor: 0 },
  ];
  const ordered = partitionCurrencyBalances(balances);
  assert.equal(ordered[0].currency, 'NGN');
  assert.equal(ordered[1].currency, 'USD');
  assert.equal(ordered[1].creditBalanceMinor, 25920);
});
