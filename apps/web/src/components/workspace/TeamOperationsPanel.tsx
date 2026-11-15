import React, { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Card, Input, Label } from '@proofly/ui';
import { normalizeProblemDetail, type ProblemDetail } from '@proofly/api-client';
import { useAuth } from '../../lib/authStore';
import { formatDecimalHours } from '../../lib/roleHelpers';

interface TeamOperationsPanelProps {
  section: 'team' | 'reviews' | 'payroll';
}

type ReviewSubTab = 'flagged' | 'leave' | 'corrections';

export const TeamOperationsPanel: React.FC<TeamOperationsPanelProps> = ({ section }) => {
  const { user, accessToken, apiClient } = useAuth();
  const queryClient = useQueryClient();

  const [siteFilter, setSiteFilter] = useState<string>('');
  const [reviewTab, setReviewTab] = useState<ReviewSubTab>('flagged');
  const [decisionReason, setDecisionReason] = useState<Record<string, string>>({});
  const [panelProblem, setPanelProblem] = useState<ProblemDetail | null>(null);
  const [panelNotice, setPanelNotice] = useState<string | null>(null);

  const todayStr = new Date().toISOString().slice(0, 10);
  const monthStartStr = `${todayStr.slice(0, 8)}01`;
  const [fromDate, setFromDate] = useState(monthStartStr);
  const [toDate, setToDate] = useState(todayStr);
  const [csvBusy, setCsvBusy] = useState(false);

  const sitesQuery = useQuery({
    queryKey: ['sites', 'visible'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/sites');
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user,
  });

  const liveBoardQuery = useQuery({
    queryKey: ['reports', 'live-board', siteFilter],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/reports/live-board', {
        params: { query: siteFilter ? { siteId: siteFilter } : undefined },
      });
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user && section === 'team',
  });

  const flaggedQuery = useQuery({
    queryKey: ['attendance', 'flagged'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/attendance/flagged');
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user && section === 'reviews',
  });

  const leaveQueueQuery = useQuery({
    queryKey: ['leave', 'requests'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/leave/requests');
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user && section === 'reviews',
  });

  const correctionsQueueQuery = useQuery({
    queryKey: ['attendance', 'corrections'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/attendance/corrections');
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user && section === 'reviews',
  });

  const payrollQuery = useQuery({
    queryKey: ['reports', 'payroll-summary', fromDate, toDate, siteFilter],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/reports/payroll-summary', {
        params: {
          query: {
            fromDate,
            toDate,
            ...(siteFilter ? { siteId: siteFilter } : {}),
          },
        },
      });
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user && section === 'payroll',
  });

  const reviewFlaggedMutation = useMutation({
    mutationFn: async (args: { id: string; decision: 'APPROVED' | 'REJECTED'; reason: string }) => {
      const { error } = await apiClient.POST('/api/v1/attendance/{eventId}/review', {
        params: { path: { eventId: args.id } },
        body: { decision: args.decision, reason: args.reason },
      });
      if (error) throw normalizeProblemDetail(error);
    },
    onSuccess: () => {
      setPanelProblem(null);
      setPanelNotice('Flagged geofence punch decision recorded in audit log.');
      queryClient.invalidateQueries({ queryKey: ['attendance', 'flagged'] });
      queryClient.invalidateQueries({ queryKey: ['reports', 'live-board'] });
    },
    onError: (err) => setPanelProblem(normalizeProblemDetail(err)),
  });

  const reviewLeaveMutation = useMutation({
    mutationFn: async (args: { id: string; decision: 'APPROVED' | 'REJECTED'; note: string }) => {
      const { error } = await apiClient.POST('/api/v1/leave/requests/{leaveId}/review', {
        params: { path: { leaveId: args.id } },
        body: { decision: args.decision, note: args.note },
      });
      if (error) throw normalizeProblemDetail(error);
    },
    onSuccess: () => {
      setPanelProblem(null);
      setPanelNotice('Leave decision saved and staff notification queued.');
      queryClient.invalidateQueries({ queryKey: ['leave', 'requests'] });
    },
    onError: (err) => setPanelProblem(normalizeProblemDetail(err)),
  });

  const reviewCorrectionMutation = useMutation({
    mutationFn: async (args: { id: string; decision: 'APPROVED' | 'REJECTED'; reason: string }) => {
      const { error } = await apiClient.POST('/api/v1/attendance/corrections/{correctionId}/review', {
        params: { path: { correctionId: args.id } },
        body: { decision: args.decision, reason: args.reason },
      });
      if (error) throw normalizeProblemDetail(error);
    },
    onSuccess: () => {
      setPanelProblem(null);
      setPanelNotice('Attendance correction decided and hours recalculated.');
      queryClient.invalidateQueries({ queryKey: ['attendance', 'corrections'] });
    },
    onError: (err) => setPanelProblem(normalizeProblemDetail(err)),
  });

  const handleDownloadCsv = async () => {
    setPanelProblem(null);
    setCsvBusy(true);
    try {
      const params = new URLSearchParams({ fromDate, toDate });
      if (siteFilter) params.set('siteId', siteFilter);
      const base = (import.meta.env?.VITE_API_BASE_URL as string | undefined) || '';
      const response = await fetch(`${base}/api/v1/reports/payroll-csv?${params.toString()}`, {
        headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
        credentials: 'include',
      });
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw normalizeProblemDetail(problem ?? new Error('Unable to export CSV'));
      }
      const csvText = await response.text();
      const blob = new Blob([csvText], { type: 'text/csv;charset=utf-8;' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `proofly-payroll-${fromDate}-to-${toDate}.csv`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
      setPanelNotice('Payroll CSV downloaded.');
    } catch (err) {
      setPanelProblem(normalizeProblemDetail(err));
    } finally {
      setCsvBusy(false);
    }
  };

  const pendingLeaves = (leaveQueueQuery.data ?? []).filter(
    (l) => l.status === 'PENDING' && l.membershipId !== user?.membershipId
  );
  const pendingCorrections = (correctionsQueueQuery.data ?? []).filter(
    (c) => c.status === 'PENDING' && c.membershipId !== user?.membershipId
  );

  return (
    <div className="space-y-6">
      {panelProblem && (
        <Alert
          tone="error"
          title={panelProblem.title}
          description={panelProblem.detail}
          meta={`${panelProblem.errorCode} · corrId ${panelProblem.correlationId}`}
        />
      )}
      {panelNotice && <Alert tone="success" title={panelNotice} />}

      {section === 'team' && (
        <>
          {/* KPI Strip */}
          {liveBoardQuery.data && (
            <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
              <Card className="p-4">
                <p className="text-xs font-medium text-[#526277]">On Site Today</p>
                <p className="mt-1 font-display text-2xl font-bold tabular-nums text-[#09A55A]">
                  {liveBoardQuery.data.summary.onSiteCount}
                </p>
              </Card>
              <Card className="p-4">
                <p className="text-xs font-medium text-[#526277]">Late Arrivals</p>
                <p className="mt-1 font-display text-2xl font-bold tabular-nums text-amber-700">
                  {liveBoardQuery.data.summary.lateCount}
                </p>
              </Card>
              <Card className="p-4">
                <p className="text-xs font-medium text-[#526277]">Flagged Geofence</p>
                <p className="mt-1 font-display text-2xl font-bold tabular-nums text-red-700">
                  {liveBoardQuery.data.summary.pendingReviewCount}
                </p>
              </Card>
              <Card className="p-4">
                <p className="text-xs font-medium text-[#526277]">On Leave / Off</p>
                <p className="mt-1 font-display text-2xl font-bold tabular-nums text-[#0B1F3A]">
                  {liveBoardQuery.data.summary.onLeaveCount} / {liveBoardQuery.data.summary.offCount}
                </p>
              </Card>
            </div>
          )}

          <Card className="p-6">
            <div className="flex flex-wrap items-center justify-between gap-4 border-b border-slate-200 pb-4">
              <div>
                <h2 className="font-display text-base font-bold text-[#0B1F3A]">
                  Live Presence Board ({liveBoardQuery.data?.date ?? todayStr})
                </h2>
                <p className="mt-0.5 text-xs text-[#526277]">
                  Scoped strictly to your managed team ({user?.role === 'MANAGER' ? 'direct reports' : 'all active staff'}).
                </p>
              </div>
              <div className="flex flex-wrap items-center gap-3">
                <select
                  aria-label="Filter live board by site"
                  value={siteFilter}
                  onChange={(e) => setSiteFilter(e.target.value)}
                  className="min-h-[40px] rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs text-[#0B1F3A]"
                >
                  <option value="">All visible sites</option>
                  {(sitesQuery.data ?? []).map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </select>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => queryClient.invalidateQueries({ queryKey: ['reports', 'live-board'] })}
                >
                  Refresh
                </Button>
              </div>
            </div>

            {liveBoardQuery.isLoading && <p className="mt-4 text-sm text-[#526277]">Loading live attendance...</p>}
            {liveBoardQuery.data && liveBoardQuery.data.rows.length === 0 && (
              <p className="mt-4 text-sm text-[#526277]">No staff visible under this filter.</p>
            )}
            {liveBoardQuery.data && liveBoardQuery.data.rows.length > 0 && (
              <div className="mt-4 overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead>
                    <tr className="border-b border-slate-200 text-xs text-[#526277]">
                      <th className="py-2 pr-4 font-semibold">Staff</th>
                      <th className="py-2 pr-4 font-semibold">Site</th>
                      <th className="py-2 pr-4 font-semibold">Status</th>
                      <th className="py-2 pr-4 font-semibold">Lateness</th>
                      <th className="py-2 pr-4 font-semibold">Distance</th>
                      <th className="py-2 font-semibold">Last Punch</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {liveBoardQuery.data.rows.map((row) => (
                      <tr key={row.membershipId}>
                        <td className="py-3 pr-4">
                          <span className="font-medium text-[#0B1F3A]">{row.fullName}</span>
                          <span className="ml-2 font-mono text-xs text-[#526277]">
                            {row.employeeCode ?? row.role}
                          </span>
                        </td>
                        <td className="py-3 pr-4 text-xs text-[#526277]">{row.siteName ?? 'Unassigned'}</td>
                        <td className="py-3 pr-4 font-mono text-xs font-semibold">
                          <span
                            className={
                              row.presenceStatus === 'ON_SITE'
                                ? 'text-[#09A55A]'
                                : row.presenceStatus === 'PENDING_REVIEW'
                                  ? 'text-red-700'
                                  : row.presenceStatus === 'LATE'
                                    ? 'text-amber-700'
                                    : 'text-[#526277]'
                            }
                          >
                            {row.presenceStatus}
                          </span>
                        </td>
                        <td className="py-3 pr-4 font-mono text-xs tabular-nums text-[#526277]">
                          {row.isLate ? `${row.lateMinutes}m late` : '—'}
                        </td>
                        <td className="py-3 pr-4 font-mono text-xs tabular-nums text-[#526277]">
                          {row.lastEventAt ? `${row.distanceMeters}m` : '—'}
                        </td>
                        <td className="py-3 font-mono text-xs tabular-nums text-[#526277]">
                          {row.lastEventAt ? new Date(row.lastEventAt).toLocaleTimeString() : 'No punch today'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        </>
      )}

      {section === 'reviews' && (
        <Card className="p-6">
          <div className="flex flex-wrap items-center justify-between gap-4 border-b border-slate-200 pb-4">
            <div>
              <h2 className="font-display text-base font-bold text-[#0B1F3A]">Manager Approval &amp; Exception Queue</h2>
              <p className="mt-0.5 text-xs text-[#526277]">
                Every approval or rejection requires a written reason and is hash-chained to the tenant audit log.
              </p>
            </div>
            <div className="flex flex-wrap gap-2" role="tablist" aria-label="Review queue types">
              <button
                type="button"
                role="tab"
                aria-selected={reviewTab === 'flagged'}
                onClick={() => setReviewTab('flagged')}
                className={`min-h-[40px] rounded-lg px-3.5 py-2 text-xs font-semibold cursor-pointer ${
                  reviewTab === 'flagged' ? 'bg-[#0B1F3A] text-white' : 'border border-slate-200 bg-white text-[#526277]'
                }`}
              >
                Out-of-Fence ({(flaggedQuery.data ?? []).length})
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={reviewTab === 'leave'}
                onClick={() => setReviewTab('leave')}
                className={`min-h-[40px] rounded-lg px-3.5 py-2 text-xs font-semibold cursor-pointer ${
                  reviewTab === 'leave' ? 'bg-[#0B1F3A] text-white' : 'border border-slate-200 bg-white text-[#526277]'
                }`}
              >
                Leave ({pendingLeaves.length})
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={reviewTab === 'corrections'}
                onClick={() => setReviewTab('corrections')}
                className={`min-h-[40px] rounded-lg px-3.5 py-2 text-xs font-semibold cursor-pointer ${
                  reviewTab === 'corrections' ? 'bg-[#0B1F3A] text-white' : 'border border-slate-200 bg-white text-[#526277]'
                }`}
              >
                Corrections ({pendingCorrections.length})
              </button>
            </div>
          </div>

          {reviewTab === 'flagged' && (
            <div className="mt-5 space-y-4">
              {flaggedQuery.isLoading && <p className="text-sm text-[#526277]">Loading flagged geofence events...</p>}
              {!flaggedQuery.isLoading && (flaggedQuery.data ?? []).length === 0 && (
                <p className="text-sm text-[#526277]">No out-of-fence punches awaiting review.</p>
              )}
              {(flaggedQuery.data ?? []).map((ev) => {
                const reasonVal = decisionReason[ev.id] ?? '';
                return (
                  <div key={ev.id} className="rounded-lg border border-slate-200 p-4 space-y-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div>
                        <p className="text-sm font-semibold text-[#0B1F3A]">
                          {ev.staffName} · {ev.eventType} at {ev.siteName}
                        </p>
                        <p className="font-mono text-xs tabular-nums text-[#526277]">
                          {new Date(ev.effectiveAt).toLocaleString()} · {ev.distanceMeters}m from center (±{ev.accuracyMeters}m GPS)
                          {ev.notes ? ` · Note: "${ev.notes}"` : ''}
                        </p>
                      </div>
                      <span className="font-mono text-xs font-semibold text-amber-700">PENDING_REVIEW</span>
                    </div>
                    <div className="flex flex-wrap items-center gap-3">
                      <Input
                        aria-label={`Review reason for ${ev.staffName}`}
                        placeholder="Written review reason (required)"
                        value={reasonVal}
                        onChange={(e) => setDecisionReason((prev) => ({ ...prev, [ev.id]: e.target.value }))}
                        className="flex-1 min-w-[220px]"
                      />
                      <Button
                        variant="primary"
                        size="sm"
                        disabled={reasonVal.trim().length < 3 || reviewFlaggedMutation.isPending}
                        onClick={() =>
                          reviewFlaggedMutation.mutate({ id: ev.id, decision: 'APPROVED', reason: reasonVal.trim() })
                        }
                      >
                        Approve
                      </Button>
                      <Button
                        variant="danger"
                        size="sm"
                        disabled={reasonVal.trim().length < 3 || reviewFlaggedMutation.isPending}
                        onClick={() =>
                          reviewFlaggedMutation.mutate({ id: ev.id, decision: 'REJECTED', reason: reasonVal.trim() })
                        }
                      >
                        Reject
                      </Button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}

          {reviewTab === 'leave' && (
            <div className="mt-5 space-y-4">
              {leaveQueueQuery.isLoading && <p className="text-sm text-[#526277]">Loading leave requests...</p>}
              {!leaveQueueQuery.isLoading && pendingLeaves.length === 0 && (
                <p className="text-sm text-[#526277]">No pending leave requests in your team queue.</p>
              )}
              {pendingLeaves.map((leave) => {
                const noteVal = decisionReason[leave.id] ?? '';
                return (
                  <div key={leave.id} className="rounded-lg border border-slate-200 p-4 space-y-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div>
                        <p className="text-sm font-semibold text-[#0B1F3A]">
                          {leave.staffName} · {leave.leaveType}
                        </p>
                        <p className="font-mono text-xs tabular-nums text-[#526277]">
                          {leave.startDate} → {leave.endDate} · Reason: {leave.reason}
                        </p>
                      </div>
                      <span className="font-mono text-xs font-semibold text-amber-700">PENDING</span>
                    </div>
                    <div className="flex flex-wrap items-center gap-3">
                      <Input
                        aria-label={`Decision note for ${leave.staffName}`}
                        placeholder="Decision note (optional)"
                        value={noteVal}
                        onChange={(e) => setDecisionReason((prev) => ({ ...prev, [leave.id]: e.target.value }))}
                        className="flex-1 min-w-[220px]"
                      />
                      <Button
                        variant="primary"
                        size="sm"
                        disabled={reviewLeaveMutation.isPending}
                        onClick={() =>
                          reviewLeaveMutation.mutate({ id: leave.id, decision: 'APPROVED', note: noteVal.trim() })
                        }
                      >
                        Approve
                      </Button>
                      <Button
                        variant="danger"
                        size="sm"
                        disabled={reviewLeaveMutation.isPending}
                        onClick={() =>
                          reviewLeaveMutation.mutate({ id: leave.id, decision: 'REJECTED', note: noteVal.trim() })
                        }
                      >
                        Reject
                      </Button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}

          {reviewTab === 'corrections' && (
            <div className="mt-5 space-y-4">
              {correctionsQueueQuery.isLoading && <p className="text-sm text-[#526277]">Loading corrections...</p>}
              {!correctionsQueueQuery.isLoading && pendingCorrections.length === 0 && (
                <p className="text-sm text-[#526277]">No pending attendance corrections to review.</p>
              )}
              {pendingCorrections.map((corr) => {
                const reasonVal = decisionReason[corr.id] ?? '';
                return (
                  <div key={corr.id} className="rounded-lg border border-slate-200 p-4 space-y-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div>
                        <p className="text-sm font-semibold text-[#0B1F3A]">
                          {corr.staffName} · {corr.eventType} ({corr.siteName})
                        </p>
                        <p className="font-mono text-xs tabular-nums text-[#526277]">
                          {new Date(corr.originalEffectiveAt).toLocaleString()} →{' '}
                          {new Date(corr.proposedEffectiveAt).toLocaleString()}
                        </p>
                        <p className="text-xs text-[#526277]">Requested reason: {corr.reason}</p>
                      </div>
                      <span className="font-mono text-xs font-semibold text-amber-700">PENDING</span>
                    </div>
                    <div className="flex flex-wrap items-center gap-3">
                      <Input
                        aria-label={`Audit decision note for ${corr.staffName}`}
                        placeholder="Written approval/rejection note (required)"
                        value={reasonVal}
                        onChange={(e) => setDecisionReason((prev) => ({ ...prev, [corr.id]: e.target.value }))}
                        className="flex-1 min-w-[220px]"
                      />
                      <Button
                        variant="primary"
                        size="sm"
                        disabled={reasonVal.trim().length < 3 || reviewCorrectionMutation.isPending}
                        onClick={() =>
                          reviewCorrectionMutation.mutate({
                            id: corr.id,
                            decision: 'APPROVED',
                            reason: reasonVal.trim(),
                          })
                        }
                      >
                        Approve
                      </Button>
                      <Button
                        variant="danger"
                        size="sm"
                        disabled={reasonVal.trim().length < 3 || reviewCorrectionMutation.isPending}
                        onClick={() =>
                          reviewCorrectionMutation.mutate({
                            id: corr.id,
                            decision: 'REJECTED',
                            reason: reasonVal.trim(),
                          })
                        }
                      >
                        Reject
                      </Button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </Card>
      )}

      {section === 'payroll' && (
        <Card className="p-6">
          <div className="flex flex-wrap items-end justify-between gap-4 border-b border-slate-200 pb-4">
            <div>
              <h2 className="font-display text-base font-bold text-[#0B1F3A]">Payroll Hours &amp; CSV Export</h2>
              <p className="mt-0.5 text-xs text-[#526277]">
                Includes verified regular hours, late minutes, and per-site overtime totals. Rejected punches are excluded.
              </p>
            </div>
            <div className="flex flex-wrap items-end gap-3">
              <div>
                <Label htmlFor="payroll-from">From</Label>
                <Input
                  id="payroll-from"
                  type="date"
                  value={fromDate}
                  onChange={(e) => setFromDate(e.target.value)}
                  className="font-mono text-xs"
                />
              </div>
              <div>
                <Label htmlFor="payroll-to">To</Label>
                <Input
                  id="payroll-to"
                  type="date"
                  value={toDate}
                  onChange={(e) => setToDate(e.target.value)}
                  className="font-mono text-xs"
                />
              </div>
              <div>
                <Label htmlFor="payroll-site">Site</Label>
                <select
                  id="payroll-site"
                  value={siteFilter}
                  onChange={(e) => setSiteFilter(e.target.value)}
                  className="min-h-[44px] rounded-lg border border-slate-300 bg-white px-3 py-2 text-xs text-[#0B1F3A]"
                >
                  <option value="">All visible sites</option>
                  {(sitesQuery.data ?? []).map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </select>
              </div>
              <Button variant="primary" size="md" onClick={handleDownloadCsv} disabled={csvBusy}>
                {csvBusy ? 'Exporting...' : 'Download Payroll CSV'}
              </Button>
            </div>
          </div>

          {payrollQuery.isLoading && <p className="mt-4 text-sm text-[#526277]">Calculating payroll hours...</p>}
          {!payrollQuery.isLoading && (payrollQuery.data ?? []).length === 0 && (
            <p className="mt-4 text-sm text-[#526277]">No completed shift attendance records in this date range.</p>
          )}
          {(payrollQuery.data ?? []).length > 0 && (
            <div className="mt-4 overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-slate-200 text-xs text-[#526277]">
                    <th className="py-2 pr-4 font-semibold">Employee</th>
                    <th className="py-2 pr-4 font-semibold">Site</th>
                    <th className="py-2 pr-4 font-semibold">Shifts</th>
                    <th className="py-2 pr-4 font-semibold">Late</th>
                    <th className="py-2 pr-4 font-semibold">Regular Hrs</th>
                    <th className="py-2 pr-4 font-semibold">Overtime Hrs</th>
                    <th className="py-2 pr-4 font-semibold">Total Hrs</th>
                    <th className="py-2 font-semibold">Flagged</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {(payrollQuery.data ?? []).map((row) => (
                    <tr key={`${row.membershipId}-${row.siteName}`}>
                      <td className="py-3 pr-4">
                        <span className="font-medium text-[#0B1F3A]">{row.fullName}</span>
                        <span className="ml-2 font-mono text-xs text-[#526277]">{row.employeeCode ?? row.role}</span>
                      </td>
                      <td className="py-3 pr-4 text-xs text-[#526277]">{row.siteName}</td>
                      <td className="py-3 pr-4 font-mono text-xs tabular-nums text-[#0B1F3A]">{row.shiftsCompleted}</td>
                      <td className="py-3 pr-4 font-mono text-xs tabular-nums text-[#526277]">
                        {row.lateArrivals} ({row.totalLateMinutes}m)
                      </td>
                      <td className="py-3 pr-4 font-mono text-xs tabular-nums text-[#0B1F3A]">
                        {formatDecimalHours(row.regularMinutes)}h
                      </td>
                      <td className="py-3 pr-4 font-mono text-xs tabular-nums text-amber-700">
                        {formatDecimalHours(row.overtimeMinutes)}h
                      </td>
                      <td className="py-3 pr-4 font-mono text-xs font-semibold tabular-nums text-[#09A55A]">
                        {formatDecimalHours(row.totalWorkedMinutes)}h
                      </td>
                      <td className="py-3 font-mono text-xs tabular-nums text-[#526277]">{row.pendingReviewCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      )}
    </div>
  );
};
