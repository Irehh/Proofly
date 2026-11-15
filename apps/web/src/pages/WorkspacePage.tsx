import React, { useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Button } from '@proofly/ui';
import { normalizeProblemDetail } from '@proofly/api-client';
import { useAuth } from '../lib/authStore';
import { PWAInstallButton } from '../pwa/PWAInstallButton';
import {
  getMobileBottomTabs,
  getVisibleWorkspaceTabs,
  hasRoleAtLeast,
  resolveSafeWorkspaceTab,
  type WorkspaceTabId,
} from '../lib/roleHelpers';
import { MyShiftPanel } from '../components/workspace/MyShiftPanel';
import { TeamOperationsPanel } from '../components/workspace/TeamOperationsPanel';
import { SitesAndStaffPanel } from '../components/workspace/SitesAndStaffPanel';
import { BillingAndGovernancePanel } from '../components/workspace/BillingAndGovernancePanel';
import { OverviewSecurityPanel } from '../components/workspace/OverviewSecurityPanel';

export const WorkspacePage: React.FC = () => {
  const { user, apiClient, logout } = useAuth();
  const [searchParams, setSearchParams] = useSearchParams();

  const tenantQuery = useQuery({
    queryKey: ['tenant', 'me'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/tenants/me');
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user,
  });

  const visibleTabs = useMemo(
    () => (user ? getVisibleWorkspaceTabs(user.role) : []),
    [user]
  );

  const mobileBottomTabs = useMemo(
    () => (user ? getMobileBottomTabs(user.role) : []),
    [user]
  );

  const activeTab: WorkspaceTabId = user
    ? resolveSafeWorkspaceTab(searchParams.get('tab'), user.role)
    : 'shift';

  const switchTab = (tab: WorkspaceTabId) => {
    setSearchParams(tab === 'shift' ? {} : { tab });
  };

  if (!user) return null;

  const groupedTabs = {
    PERSONAL: visibleTabs.filter((t) => t.group === 'PERSONAL'),
    OPERATIONS: visibleTabs.filter((t) => t.group === 'OPERATIONS'),
    ADMINISTRATION: visibleTabs.filter((t) => t.group === 'ADMINISTRATION'),
  };

  const activeTabMeta = visibleTabs.find((t) => t.id === activeTab) ?? visibleTabs[0];

  return (
    <div className="mx-auto max-w-7xl px-4 py-4 pb-20 sm:px-6 lg:py-8 lg:pb-10">
      {/* Workspace Header */}
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 pb-3.5 sm:pb-5">
        <div>
          <h1 className="font-display text-xl font-bold text-[#0B1F3A] sm:text-2xl">
            {tenantQuery.data?.name ?? 'Proofly Workspace'}
          </h1>
          <p className="mt-0.5 text-xs text-[#526277] sm:mt-1 sm:text-sm">
            {user.fullName} · {user.role} · {user.email ?? user.phoneE164}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2 sm:gap-3">
          <PWAInstallButton />
          <Button variant="outline" size="sm" onClick={logout}>
            Sign out
          </Button>
        </div>
      </div>

      {/* Mobile Section Selector for additional Admin/Manager sections */}
      {visibleTabs.length > mobileBottomTabs.length && (
        <div className="mt-3 lg:hidden">
          <label htmlFor="mobile-workspace-section" className="sr-only">
            Switch workspace section
          </label>
          <select
            id="mobile-workspace-section"
            value={activeTab}
            onChange={(e) => switchTab(e.target.value as WorkspaceTabId)}
            className="w-full min-h-[44px] rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-medium text-[#0B1F3A]"
          >
            {visibleTabs.map((tab) => (
              <option key={tab.id} value={tab.id}>
                {tab.label}
              </option>
            ))}
          </select>
        </div>
      )}

      <div className="mt-4 flex flex-col gap-5 lg:mt-6 lg:flex-row lg:items-start lg:gap-6">
        {/* Desktop Sidebar (256px) */}
        <aside className="hidden lg:block lg:w-64 shrink-0">
          <nav aria-label="Workspace sections" className="rounded-xl border border-slate-200 bg-white p-3 space-y-5">
            <div>
              <p className="px-3 pb-1.5 text-[11px] font-semibold uppercase tracking-wider text-[#526277]">
                Personal
              </p>
              <ul className="space-y-1">
                {groupedTabs.PERSONAL.map((tab) => (
                  <li key={tab.id}>
                    <button
                      type="button"
                      onClick={() => switchTab(tab.id)}
                      aria-current={activeTab === tab.id ? 'page' : undefined}
                      className={`w-full min-h-[40px] rounded-lg px-3 py-2 text-left text-sm font-medium transition-colors cursor-pointer ${
                        activeTab === tab.id
                          ? 'bg-[#0B1F3A] text-white'
                          : 'text-[#526277] hover:bg-[#F7F9FC] hover:text-[#0B1F3A]'
                      }`}
                    >
                      {tab.label}
                    </button>
                  </li>
                ))}
              </ul>
            </div>

            {groupedTabs.OPERATIONS.length > 0 && (
              <div>
                <p className="px-3 pb-1.5 text-[11px] font-semibold uppercase tracking-wider text-[#526277]">
                  Team Operations
                </p>
                <ul className="space-y-1">
                  {groupedTabs.OPERATIONS.map((tab) => (
                    <li key={tab.id}>
                      <button
                        type="button"
                        onClick={() => switchTab(tab.id)}
                        aria-current={activeTab === tab.id ? 'page' : undefined}
                        className={`w-full min-h-[40px] rounded-lg px-3 py-2 text-left text-sm font-medium transition-colors cursor-pointer ${
                          activeTab === tab.id
                            ? 'bg-[#0B1F3A] text-white'
                            : 'text-[#526277] hover:bg-[#F7F9FC] hover:text-[#0B1F3A]'
                        }`}
                      >
                        {tab.label}
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {groupedTabs.ADMINISTRATION.length > 0 && (
              <div>
                <p className="px-3 pb-1.5 text-[11px] font-semibold uppercase tracking-wider text-[#526277]">
                  Administration
                </p>
                <ul className="space-y-1">
                  {groupedTabs.ADMINISTRATION.map((tab) => (
                    <li key={tab.id}>
                      <button
                        type="button"
                        onClick={() => switchTab(tab.id)}
                        aria-current={activeTab === tab.id ? 'page' : undefined}
                        className={`w-full min-h-[40px] rounded-lg px-3 py-2 text-left text-sm font-medium transition-colors cursor-pointer ${
                          activeTab === tab.id
                            ? 'bg-[#0B1F3A] text-white'
                            : 'text-[#526277] hover:bg-[#F7F9FC] hover:text-[#0B1F3A]'
                        }`}
                      >
                        {tab.label}
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </nav>
        </aside>

        {/* Active Role Panel */}
        <section className="flex-1 min-w-0 space-y-4" aria-label={activeTabMeta?.label ?? 'Workspace panel'}>
          {activeTab === 'shift' && <MyShiftPanel />}

          {activeTab === 'team' && hasRoleAtLeast(user.role, 'MANAGER') && (
            <TeamOperationsPanel section="team" />
          )}

          {activeTab === 'reviews' && hasRoleAtLeast(user.role, 'MANAGER') && (
            <TeamOperationsPanel section="reviews" />
          )}

          {activeTab === 'payroll' && hasRoleAtLeast(user.role, 'MANAGER') && (
            <TeamOperationsPanel section="payroll" />
          )}

          {activeTab === 'sites' && hasRoleAtLeast(user.role, 'ADMIN') && (
            <SitesAndStaffPanel section="sites" />
          )}

          {activeTab === 'staff' && hasRoleAtLeast(user.role, 'ADMIN') && (
            <SitesAndStaffPanel section="staff" />
          )}

          {activeTab === 'billing' && hasRoleAtLeast(user.role, 'ADMIN') && (
            <BillingAndGovernancePanel section="billing" />
          )}

          {activeTab === 'outbox' && hasRoleAtLeast(user.role, 'ADMIN') && (
            <BillingAndGovernancePanel section="outbox" />
          )}

          {activeTab === 'audit' && hasRoleAtLeast(user.role, 'ADMIN') && (
            <BillingAndGovernancePanel section="audit" />
          )}

          {activeTab === 'overview' && <OverviewSecurityPanel />}
        </section>
      </div>

      {/* Compact Mobile Sticky Thumb Navigation (56px <= 15% viewport height) */}
      <nav
        aria-label="Mobile workspace navigation"
        className="fixed bottom-0 inset-x-0 z-30 flex h-14 items-center justify-around border-t border-slate-200 bg-white px-2 lg:hidden"
      >
        {mobileBottomTabs.map((tab) => {
          const isCurrent = activeTab === tab.id;
          return (
            <button
              key={tab.id}
              type="button"
              onClick={() => switchTab(tab.id)}
              aria-current={isCurrent ? 'page' : undefined}
              className={`min-h-[44px] flex-1 rounded-lg px-2 py-1 text-center text-xs font-semibold transition-colors cursor-pointer ${
                isCurrent ? 'text-[#09A55A]' : 'text-[#526277]'
              }`}
            >
              {tab.mobileLabel}
            </button>
          );
        })}
      </nav>
    </div>
  );
};
