import React, { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Button, Card, Input, Label } from '@proofly/ui';
import {
  normalizeProblemDetail,
  type AttendanceEventView,
  type ProblemDetail,
  type SiteAssignmentView,
} from '@proofly/api-client';
import { useAuth } from '../../lib/authStore';
import {
  correctionRequestSchema,
  leaveRequestSchema,
  type CorrectionRequestFormValues,
  type LeaveRequestFormValues,
} from '../../lib/validation';
import { deriveActiveOpenClockIn, formatDurationMinutes } from '../../lib/roleHelpers';

export const MyShiftPanel: React.FC = () => {
  const { user, apiClient } = useAuth();
  const queryClient = useQueryClient();

  const [isOnline, setIsOnline] = useState(() =>
    typeof navigator !== 'undefined' ? navigator.onLine : true
  );
  const [selectedSiteId, setSelectedSiteId] = useState<string>('');
  const [coords, setCoords] = useState<{
    lat: number;
    lng: number;
    accuracy: number;
    source: 'GPS' | 'MANUAL';
  } | null>(null);
  const [gpsError, setGpsError] = useState<string | null>(null);
  const [gpsBusy, setGpsBusy] = useState(false);
  const [manualMode, setManualMode] = useState(false);
  const [manualLat, setManualLat] = useState('6.4501');
  const [manualLng, setManualLng] = useState('3.3622');
  const [manualAcc, setManualAcc] = useState('15');
  const [punchNotes, setPunchNotes] = useState('');

  const [panelProblem, setPanelProblem] = useState<ProblemDetail | null>(null);
  const [panelNotice, setPanelNotice] = useState<string | null>(null);
  const [correctionTarget, setCorrectionTarget] = useState<AttendanceEventView | null>(null);

  useEffect(() => {
    const onOnline = () => setIsOnline(true);
    const onOffline = () => setIsOnline(false);
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);
    return () => {
      window.removeEventListener('online', onOnline);
      window.removeEventListener('offline', onOffline);
    };
  }, []);

  const assignmentsQuery = useQuery({
    queryKey: ['sites', 'assignments', 'me'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/sites/assignments/me');
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user,
  });

  const visibleSitesQuery = useQuery({
    queryKey: ['sites', 'visible'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/sites');
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user,
  });

  const myAttendanceQuery = useQuery({
    queryKey: ['attendance', 'me'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/attendance/me', {
        params: { query: { limit: 30 } },
      });
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user,
  });

  const myCorrectionsQuery = useQuery({
    queryKey: ['attendance', 'corrections'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/attendance/corrections');
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user,
  });

  const myLeavesQuery = useQuery({
    queryKey: ['leave', 'requests'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/leave/requests');
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user,
  });

  const siteChoices = useMemo<SiteAssignmentView[]>(() => {
    const assigned = assignmentsQuery.data ?? [];
    if (assigned.length > 0) return assigned;
    const isAdmin = user?.role === 'ADMIN' || user?.role === 'SUPER_ADMIN';
    if (!isAdmin) return [];
    return (visibleSitesQuery.data ?? [])
      .filter((s) => s.isActive)
      .map((s, idx) => ({
        id: s.id,
        membershipId: user?.membershipId ?? '',
        siteId: s.id,
        siteName: s.name,
        siteCode: s.code,
        timezone: s.timezone,
        isPrimary: idx === 0,
        shiftStartLocal: s.shiftStartLocal,
        lateGraceMinutes: s.lateGraceMinutes,
        overtimeThresholdMinutes: s.overtimeThresholdMinutes,
      }));
  }, [assignmentsQuery.data, visibleSitesQuery.data, user]);

  useEffect(() => {
    if (!selectedSiteId && siteChoices.length > 0) {
      const primary = siteChoices.find((s) => s.isPrimary) ?? siteChoices[0];
      setSelectedSiteId(primary.siteId);
    }
  }, [selectedSiteId, siteChoices]);

  const selectedSiteRule = siteChoices.find((s) => s.siteId === selectedSiteId) ?? null;
  const openShift = useMemo(
    () => deriveActiveOpenClockIn(myAttendanceQuery.data ?? []),
    [myAttendanceQuery.data]
  );

  const captureGpsLocation = () => {
    setGpsError(null);
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      setGpsError('Browser geolocation is unavailable on this device. Switch to manual coordinates below to test.');
      return;
    }
    setGpsBusy(true);
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setGpsBusy(false);
        setManualMode(false);
        setCoords({
          lat: Number(pos.coords.latitude.toFixed(6)),
          lng: Number(pos.coords.longitude.toFixed(6)),
          accuracy: Math.round(pos.coords.accuracy || 20),
          source: 'GPS',
        });
      },
      (err) => {
        setGpsBusy(false);
        setGpsError(err.message || 'Location permission denied. Enable GPS or enter manual coordinates for testing.');
      },
      { enableHighAccuracy: true, timeout: 10_000, maximumAge: 0 }
    );
  };

  const applyManualCoords = () => {
    const lat = Number(manualLat);
    const lng = Number(manualLng);
    const acc = Number(manualAcc);
    if (Number.isNaN(lat) || lat < -90 || lat > 90 || Number.isNaN(lng) || lng < -180 || lng > 180) {
      setGpsError('Enter valid latitude (-90..90) and longitude (-180..180).');
      return;
    }
    setGpsError(null);
    setCoords({
      lat,
      lng,
      accuracy: Number.isNaN(acc) || acc < 0 ? 15 : Math.round(acc),
      source: 'MANUAL',
    });
  };

  const clockMutation = useMutation({
    mutationFn: async (kind: 'IN' | 'OUT') => {
      if (!isOnline) {
        throw normalizeProblemDetail(
          new Error('Active internet connection is required to verify and record attendance.')
        );
      }
      if (!coords) {
        throw normalizeProblemDetail(new Error('Capture your GPS location before clocking in or out.'));
      }
      const targetSiteId = kind === 'OUT' && openShift ? openShift.siteId : selectedSiteId;
      if (!targetSiteId) {
        throw normalizeProblemDetail(new Error('Select an assigned work site first.'));
      }
      const body = {
        siteId: targetSiteId,
        latitude: coords.lat,
        longitude: coords.lng,
        accuracyMeters: coords.accuracy,
        notes: punchNotes.trim() || undefined,
      };
      const res =
        kind === 'IN'
          ? await apiClient.POST('/api/v1/attendance/clock-in', { body })
          : await apiClient.POST('/api/v1/attendance/clock-out', { body });
      const maybeError = (res as { error?: unknown }).error;
      if (maybeError || !res.data) throw normalizeProblemDetail(maybeError);
      return res.data;
    },
    onSuccess: (event) => {
      setPanelProblem(null);
      setPunchNotes('');
      queryClient.invalidateQueries({ queryKey: ['attendance', 'me'] });
      if (event.geofenceStatus === 'PENDING_REVIEW') {
        setPanelNotice(
          `${event.eventType === 'CLOCK_IN' ? 'Clock-in' : 'Clock-out'} recorded at ${event.distanceMeters}m from geofence and flagged PENDING_REVIEW for your manager.`
        );
      } else {
        setPanelNotice(
          `${event.eventType === 'CLOCK_IN' ? 'Clocked in' : 'Clocked out'} at ${event.siteName} (${event.distanceMeters}m verified).`
        );
      }
    },
    onError: (err) => setPanelProblem(normalizeProblemDetail(err)),
  });

  const correctionForm = useForm<CorrectionRequestFormValues>({
    resolver: zodResolver(correctionRequestSchema),
    defaultValues: {
      attendanceEventId: '',
      proposedLocalDateTime: '',
      reason: '',
    },
  });

  const openCorrectionDrawer = (ev: AttendanceEventView) => {
    setCorrectionTarget(ev);
    const localIso = new Date(ev.effectiveAt).toISOString().slice(0, 16);
    correctionForm.reset({
      attendanceEventId: ev.id,
      proposedLocalDateTime: localIso,
      reason: '',
    });
  };

  const submitCorrection = correctionForm.handleSubmit(async (values) => {
    setPanelProblem(null);
    const isoTimestamp = new Date(values.proposedLocalDateTime).toISOString();
    const { error } = await apiClient.POST('/api/v1/attendance/corrections', {
      body: {
        attendanceEventId: values.attendanceEventId,
        proposedEffectiveAt: isoTimestamp,
        reason: values.reason,
      },
    });
    if (error) {
      setPanelProblem(normalizeProblemDetail(error));
      return;
    }
    setCorrectionTarget(null);
    setPanelNotice('Attendance correction submitted with written reason for manager approval.');
    queryClient.invalidateQueries({ queryKey: ['attendance', 'corrections'] });
  });

  const leaveForm = useForm<LeaveRequestFormValues>({
    resolver: zodResolver(leaveRequestSchema),
    defaultValues: {
      leaveType: 'ANNUAL',
      startDate: new Date().toISOString().slice(0, 10),
      endDate: new Date().toISOString().slice(0, 10),
      reason: '',
    },
  });

  const submitLeave = leaveForm.handleSubmit(async (values) => {
    setPanelProblem(null);
    const { error } = await apiClient.POST('/api/v1/leave/requests', {
      body: values,
    });
    if (error) {
      setPanelProblem(normalizeProblemDetail(error));
      return;
    }
    leaveForm.reset({
      leaveType: 'ANNUAL',
      startDate: new Date().toISOString().slice(0, 10),
      endDate: new Date().toISOString().slice(0, 10),
      reason: '',
    });
    setPanelNotice('Leave request submitted and routed to your assigned manager.');
    queryClient.invalidateQueries({ queryKey: ['leave', 'requests'] });
  });

  const cancelLeaveMutation = useMutation({
    mutationFn: async (leaveId: string) => {
      const { error } = await apiClient.POST('/api/v1/leave/requests/{leaveId}/cancel', {
        params: { path: { leaveId } },
      });
      if (error) throw normalizeProblemDetail(error);
    },
    onSuccess: () => {
      setPanelNotice('Pending leave request canceled.');
      queryClient.invalidateQueries({ queryKey: ['leave', 'requests'] });
    },
    onError: (err) => setPanelProblem(normalizeProblemDetail(err)),
  });

  const myLeaves = (myLeavesQuery.data ?? []).filter((l) => l.membershipId === user?.membershipId);

  return (
    <div className="space-y-6">
      {!isOnline && (
        <Alert
          tone="error"
          title="Live connection required"
          description="You are currently offline. Proofly requires a live internet connection to compute PostGIS geofence distance and record attendance punches."
        />
      )}

      {panelProblem && (
        <Alert
          tone="error"
          title={panelProblem.title}
          description={panelProblem.detail}
          meta={`${panelProblem.errorCode} · corrId ${panelProblem.correlationId}`}
        />
      )}

      {panelNotice && <Alert tone="success" title={panelNotice} />}

      {/* Shift Punch & Site Selection */}
      <div className="grid grid-cols-1 gap-5 lg:grid-cols-12 lg:gap-6">
        <Card className="p-4 sm:p-6 lg:col-span-6">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 pb-3 sm:pb-4">
            <div>
              <h2 className="font-display text-lg font-bold text-[#0B1F3A]">
                {openShift ? 'Active Shift In Progress' : 'Shift Check-In'}
              </h2>
              <p className="mt-0.5 text-xs text-[#526277]">
                {isOnline ? 'Connection online · Live geofence check active' : 'Offline · Punching disabled'}
              </p>
            </div>
            <span
              className={`font-mono text-xs font-semibold ${
                openShift ? 'text-[#09A55A]' : 'text-[#526277]'
              }`}
            >
              {openShift ? 'ON SHIFT' : 'OFF SHIFT'}
            </span>
          </div>

          <div className="mt-3.5 space-y-3 sm:mt-5 sm:space-y-4">
            {openShift ? (
              <div className="rounded-lg border border-slate-200 bg-[#F7F9FC] p-3 sm:p-4 text-sm">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-semibold text-[#0B1F3A]">{openShift.siteName}</span>
                  <span
                    className={`font-mono text-xs font-semibold ${
                      openShift.geofenceStatus === 'PENDING_REVIEW' ? 'text-amber-700' : 'text-[#09A55A]'
                    }`}
                  >
                    {openShift.geofenceStatus}
                  </span>
                </div>
                <p className="mt-1 font-mono text-xs tabular-nums text-[#526277]">
                  Clocked in {new Date(openShift.effectiveAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })} · {openShift.distanceMeters}m from center
                  {openShift.isLate ? ` · ${openShift.lateMinutes}m late` : ''}
                </p>
              </div>
            ) : (
              <div>
                <Label htmlFor="shift-site-select">Assigned work site</Label>
                {assignmentsQuery.isLoading ? (
                  <p className="text-sm text-[#526277]">Loading your assigned sites...</p>
                ) : siteChoices.length === 0 ? (
                  <Alert
                    tone="info"
                    title="No assigned site yet"
                    description="Ask your organization admin to assign you to one or more geofenced work sites."
                  />
                ) : (
                  <>
                    <select
                      id="shift-site-select"
                      value={selectedSiteId}
                      onChange={(e) => setSelectedSiteId(e.target.value)}
                      className="w-full min-h-[44px] rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-[#0B1F3A]"
                    >
                      {siteChoices.map((site) => (
                        <option key={site.siteId} value={site.siteId}>
                          {site.siteName} ({site.siteCode}) {site.isPrimary ? '· Primary' : ''}
                        </option>
                      ))}
                    </select>
                    {selectedSiteRule && (
                      <p className="mt-2 font-mono text-xs tabular-nums text-[#526277]">
                        Shift {selectedSiteRule.shiftStartLocal} · Grace {selectedSiteRule.lateGraceMinutes}m · OT after{' '}
                        {formatDurationMinutes(selectedSiteRule.overtimeThresholdMinutes)} · {selectedSiteRule.timezone}
                      </p>
                    )}
                  </>
                )}
              </div>
            )}

            {/* Location Capture */}
            <div className="rounded-lg border border-slate-200 bg-[#F7F9FC] p-3 sm:p-4 space-y-2.5 sm:space-y-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-xs font-semibold text-[#0B1F3A]">Device GPS Coordinates</span>
                <div className="flex items-center gap-2">
                  <Button variant="outline" size="sm" onClick={captureGpsLocation} disabled={gpsBusy}>
                    {gpsBusy ? 'Locating...' : 'Capture GPS'}
                  </Button>
                  <button
                    type="button"
                    onClick={() => setManualMode((v) => !v)}
                    className="min-h-[36px] px-2 text-xs font-medium text-[#526277] underline underline-offset-4 hover:text-[#0B1F3A] cursor-pointer"
                  >
                    {manualMode ? 'Hide test input' : 'Manual GPS'}
                  </button>
                </div>
              </div>

              {coords ? (
                <p className="font-mono text-xs tabular-nums text-[#0B1F3A]">
                  {coords.lat.toFixed(5)}, {coords.lng.toFixed(5)} · ±{coords.accuracy}m · {coords.source}
                </p>
              ) : (
                <p className="text-xs text-[#526277]">
                  Capture GPS to punch. Out-of-fence attempts are flagged for review, never dropped.
                </p>
              )}

              {gpsError && <p className="text-xs text-red-700">{gpsError}</p>}

              {manualMode && (
                <div className="grid grid-cols-3 gap-2 pt-2 border-t border-slate-200">
                  <div>
                    <Label htmlFor="manual-lat">Lat</Label>
                    <Input
                      id="manual-lat"
                      value={manualLat}
                      onChange={(e) => setManualLat(e.target.value)}
                      className="font-mono text-xs tabular-nums"
                    />
                  </div>
                  <div>
                    <Label htmlFor="manual-lng">Lng</Label>
                    <Input
                      id="manual-lng"
                      value={manualLng}
                      onChange={(e) => setManualLng(e.target.value)}
                      className="font-mono text-xs tabular-nums"
                    />
                  </div>
                  <div>
                    <Label htmlFor="manual-acc">Acc (m)</Label>
                    <Input
                      id="manual-acc"
                      value={manualAcc}
                      onChange={(e) => setManualAcc(e.target.value)}
                      className="font-mono text-xs tabular-nums"
                    />
                  </div>
                  <div className="col-span-3">
                    <Button variant="secondary" size="sm" onClick={applyManualCoords} className="w-full">
                      Apply Test Coordinates
                    </Button>
                  </div>
                </div>
              )}
            </div>

            <div>
              <Label htmlFor="punch-notes">Shift note (optional, helpful if outside fence)</Label>
              <Input
                id="punch-notes"
                placeholder="e.g. Unloading at overflow yard gate 2"
                value={punchNotes}
                onChange={(e) => setPunchNotes(e.target.value)}
              />
            </div>

            {openShift ? (
              <Button
                variant="secondary"
                size="lg"
                className="w-full min-h-[48px]"
                disabled={!isOnline || !coords || clockMutation.isPending}
                onClick={() => clockMutation.mutate('OUT')}
              >
                {clockMutation.isPending ? 'Recording Clock-Out...' : 'Clock Out of Shift'}
              </Button>
            ) : (
              <Button
                variant="primary"
                size="lg"
                className="w-full min-h-[48px]"
                disabled={!isOnline || !coords || !selectedSiteId || clockMutation.isPending}
                onClick={() => clockMutation.mutate('IN')}
              >
                {clockMutation.isPending ? 'Verifying Geofence...' : 'Clock In Now'}
              </Button>
            )}
          </div>
        </Card>

        {/* Request Leave */}
        <Card className="p-6 lg:col-span-6">
          <div className="border-b border-slate-200 pb-4">
            <h2 className="font-display text-lg font-bold text-[#0B1F3A]">Request Leave</h2>
            <p className="mt-0.5 text-xs text-[#526277]">
              Submitted requests route automatically to your assigned manager for approval.
            </p>
          </div>

          <form onSubmit={submitLeave} className="mt-5 space-y-4" noValidate>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <div>
                <Label htmlFor="leave-type">Leave type</Label>
                <select
                  id="leave-type"
                  className="w-full min-h-[44px] rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-[#0B1F3A]"
                  {...leaveForm.register('leaveType')}
                >
                  <option value="ANNUAL">Annual</option>
                  <option value="SICK">Sick</option>
                  <option value="UNPAID">Unpaid</option>
                  <option value="OTHER">Other</option>
                </select>
              </div>
              <div>
                <Label htmlFor="leave-start">Start date</Label>
                <Input id="leave-start" type="date" className="font-mono text-xs" {...leaveForm.register('startDate')} />
                {leaveForm.formState.errors.startDate && (
                  <p className="mt-1 text-xs text-red-700">{leaveForm.formState.errors.startDate.message}</p>
                )}
              </div>
              <div>
                <Label htmlFor="leave-end">End date</Label>
                <Input id="leave-end" type="date" className="font-mono text-xs" {...leaveForm.register('endDate')} />
                {leaveForm.formState.errors.endDate && (
                  <p className="mt-1 text-xs text-red-700">{leaveForm.formState.errors.endDate.message}</p>
                )}
              </div>
            </div>

            <div>
              <Label htmlFor="leave-reason">Reason</Label>
              <Input
                id="leave-reason"
                placeholder="Brief details for your manager"
                {...leaveForm.register('reason')}
              />
              {leaveForm.formState.errors.reason && (
                <p className="mt-1 text-xs text-red-700">{leaveForm.formState.errors.reason.message}</p>
              )}
            </div>

            <Button type="submit" variant="secondary" size="md" disabled={leaveForm.formState.isSubmitting}>
              {leaveForm.formState.isSubmitting ? 'Submitting...' : 'Submit Leave Request'}
            </Button>
          </form>

          <div className="mt-6 border-t border-slate-200 pt-4">
            <h3 className="text-xs font-semibold text-[#0B1F3A]">My Leave History</h3>
            {myLeavesQuery.isLoading && <p className="mt-2 text-xs text-[#526277]">Loading leave history...</p>}
            {!myLeavesQuery.isLoading && myLeaves.length === 0 && (
              <p className="mt-2 text-xs text-[#526277]">No leave requests submitted yet.</p>
            )}
            {myLeaves.length > 0 && (
              <ul className="mt-2 divide-y divide-slate-100 text-xs">
                {myLeaves.slice(0, 5).map((leave) => (
                  <li key={leave.id} className="flex flex-wrap items-center justify-between gap-2 py-2.5">
                    <div>
                      <span className="font-semibold text-[#0B1F3A]">{leave.leaveType}</span>
                      <span className="ml-2 font-mono tabular-nums text-[#526277]">
                        {leave.startDate} → {leave.endDate}
                      </span>
                      <p className="text-[#526277]">{leave.reason}</p>
                    </div>
                    <div className="flex items-center gap-2">
                      <span
                        className={`font-mono font-semibold ${
                          leave.status === 'APPROVED'
                            ? 'text-[#09A55A]'
                            : leave.status === 'REJECTED'
                              ? 'text-red-700'
                              : leave.status === 'PENDING'
                                ? 'text-amber-700'
                                : 'text-[#526277]'
                        }`}
                      >
                        {leave.status}
                      </span>
                      {leave.status === 'PENDING' && (
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => cancelLeaveMutation.mutate(leave.id)}
                          disabled={cancelLeaveMutation.isPending}
                        >
                          Cancel
                        </Button>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </Card>
      </div>

      {/* Inline Correction Modal/Drawer */}
      {correctionTarget && (
        <Card className="p-6">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 pb-3">
            <div>
              <h3 className="font-display text-base font-bold text-[#0B1F3A]">
                Request Attendance Correction
              </h3>
              <p className="text-xs text-[#526277]">
                {correctionTarget.eventType} at {correctionTarget.siteName} · Original{' '}
                {new Date(correctionTarget.effectiveAt).toLocaleString()}
              </p>
            </div>
            <Button variant="outline" size="sm" onClick={() => setCorrectionTarget(null)}>
              Close
            </Button>
          </div>

          <form onSubmit={submitCorrection} className="mt-4 grid grid-cols-1 gap-4 md:grid-cols-12" noValidate>
            <div className="md:col-span-4">
              <Label htmlFor="corr-proposed-dt">Proposed timestamp</Label>
              <Input
                id="corr-proposed-dt"
                type="datetime-local"
                className="font-mono text-xs"
                {...correctionForm.register('proposedLocalDateTime')}
              />
              {correctionForm.formState.errors.proposedLocalDateTime && (
                <p className="mt-1 text-xs text-red-700">
                  {correctionForm.formState.errors.proposedLocalDateTime.message}
                </p>
              )}
            </div>
            <div className="md:col-span-6">
              <Label htmlFor="corr-reason">Audit reason (who/when/why)</Label>
              <Input
                id="corr-reason"
                placeholder="e.g. Gate tablet lost signal; supervisor saw arrival at 08:02"
                {...correctionForm.register('reason')}
              />
              {correctionForm.formState.errors.reason && (
                <p className="mt-1 text-xs text-red-700">{correctionForm.formState.errors.reason.message}</p>
              )}
            </div>
            <div className="md:col-span-2 flex items-end">
              <Button
                type="submit"
                variant="primary"
                size="md"
                className="w-full"
                disabled={correctionForm.formState.isSubmitting}
              >
                Submit
              </Button>
            </div>
          </form>
        </Card>
      )}

      {/* Personal Attendance Log */}
      <Card className="p-6">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <h2 className="font-display text-base font-bold text-[#0B1F3A]">My Attendance Log</h2>
            <p className="mt-0.5 text-xs text-[#526277]">
              Every punch records PostGIS distance, accuracy, late/overtime calculation, and correction history.
            </p>
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={() => queryClient.invalidateQueries({ queryKey: ['attendance', 'me'] })}
          >
            Refresh
          </Button>
        </div>

        {myAttendanceQuery.isLoading && <p className="mt-4 text-sm text-[#526277]">Loading attendance log...</p>}
        {!myAttendanceQuery.isLoading && (myAttendanceQuery.data ?? []).length === 0 && (
          <p className="mt-4 text-sm text-[#526277]">No clock-in or clock-out punches recorded yet.</p>
        )}
        {(myAttendanceQuery.data ?? []).length > 0 && (
          <div className="mt-4 overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b border-slate-200 text-xs text-[#526277]">
                  <th className="py-2 pr-4 font-semibold">Event</th>
                  <th className="py-2 pr-4 font-semibold">Site</th>
                  <th className="py-2 pr-4 font-semibold">Effective Time</th>
                  <th className="py-2 pr-4 font-semibold">Distance</th>
                  <th className="py-2 pr-4 font-semibold">Geofence</th>
                  <th className="py-2 pr-4 font-semibold">Shift Metrics</th>
                  <th className="py-2 text-right font-semibold">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {(myAttendanceQuery.data ?? []).map((ev) => (
                  <tr key={ev.id}>
                    <td className="py-3 pr-4 font-mono text-xs font-semibold text-[#0B1F3A]">
                      {ev.eventType}
                    </td>
                    <td className="py-3 pr-4 text-xs text-[#0B1F3A]">{ev.siteName}</td>
                    <td className="py-3 pr-4 font-mono text-xs tabular-nums text-[#526277]">
                      {new Date(ev.effectiveAt).toLocaleString()}
                    </td>
                    <td className="py-3 pr-4 font-mono text-xs tabular-nums text-[#526277]">
                      {ev.distanceMeters}m (±{ev.accuracyMeters}m)
                    </td>
                    <td className="py-3 pr-4 font-mono text-xs font-semibold">
                      <span
                        className={
                          ev.geofenceStatus === 'VERIFIED' || ev.geofenceStatus === 'APPROVED'
                            ? 'text-[#09A55A]'
                            : ev.geofenceStatus === 'PENDING_REVIEW'
                              ? 'text-amber-700'
                              : 'text-red-700'
                        }
                      >
                        {ev.geofenceStatus}
                      </span>
                    </td>
                    <td className="py-3 pr-4 font-mono text-xs tabular-nums text-[#526277]">
                      {ev.eventType === 'CLOCK_IN'
                        ? ev.isLate
                          ? `${ev.lateMinutes}m late`
                          : 'On time'
                        : `${formatDurationMinutes(ev.workedMinutes)}${
                            ev.overtimeMinutes > 0 ? ` · ${ev.overtimeMinutes}m OT` : ''
                          }`}
                    </td>
                    <td className="py-3 text-right">
                      <Button variant="outline" size="sm" onClick={() => openCorrectionDrawer(ev)}>
                        Correct
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {(myCorrectionsQuery.data ?? []).filter((c) => c.membershipId === user?.membershipId).length > 0 && (
          <div className="mt-6 border-t border-slate-200 pt-4">
            <h3 className="text-xs font-semibold text-[#0B1F3A]">My Correction Requests</h3>
            <ul className="mt-2 divide-y divide-slate-100 text-xs">
              {(myCorrectionsQuery.data ?? [])
                .filter((c) => c.membershipId === user?.membershipId)
                .map((c) => (
                  <li key={c.id} className="flex flex-wrap items-center justify-between gap-2 py-2">
                    <div>
                      <span className="font-medium text-[#0B1F3A]">
                        {c.siteName} · {c.eventType}
                      </span>
                      <span className="ml-2 font-mono tabular-nums text-[#526277]">
                        → {new Date(c.proposedEffectiveAt).toLocaleString()}
                      </span>
                      <p className="text-[#526277]">
                        Reason: {c.reason}
                        {c.reviewNote ? ` · Review: ${c.reviewNote}` : ''}
                      </p>
                    </div>
                    <span
                      className={`font-mono font-semibold ${
                        c.status === 'APPROVED'
                          ? 'text-[#09A55A]'
                          : c.status === 'REJECTED'
                            ? 'text-red-700'
                            : 'text-amber-700'
                      }`}
                    >
                      {c.status}
                    </span>
                  </li>
                ))}
            </ul>
          </div>
        )}
      </Card>
    </div>
  );
};
