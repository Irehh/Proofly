import React, { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Button, Card, Input, Label, PROOFLY_PLANS } from '@proofly/ui';
import {
  normalizeProblemDetail,
  type MemberSummary,
  type ProblemDetail,
  type Role,
  type SiteAssignmentItem,
  type SiteView,
} from '@proofly/api-client';
import { useAuth } from '../../lib/authStore';
import {
  createSiteSchema,
  inviteStaffSchema,
  orgSettingsSchema,
  type CreateSiteFormValues,
  type InviteStaffFormValues,
  type OrgSettingsFormValues,
} from '../../lib/validation';
import { canEditMemberRole } from '../../lib/roleHelpers';

interface SitesAndStaffPanelProps {
  section: 'sites' | 'staff';
}

interface AssignmentDraftRow {
  selected: boolean;
  isPrimary: boolean;
  shiftStartOverride: string;
  lateGraceOverride: string;
  overtimeThresholdOverride: string;
}

export const SitesAndStaffPanel: React.FC<SitesAndStaffPanelProps> = ({ section }) => {
  const { user, apiClient } = useAuth();
  const queryClient = useQueryClient();

  const [panelProblem, setPanelProblem] = useState<ProblemDetail | null>(null);
  const [panelNotice, setPanelNotice] = useState<string | null>(null);
  const [assignTarget, setAssignTarget] = useState<MemberSummary | null>(null);
  const [assignDraft, setAssignDraft] = useState<Record<string, AssignmentDraftRow>>({});
  const [govTarget, setGovTarget] = useState<MemberSummary | null>(null);
  const [govRole, setGovRole] = useState<Role>('EMPLOYEE');
  const [govStatus, setGovStatus] = useState<'ACTIVE' | 'SUSPENDED' | 'OFFBOARDED'>('ACTIVE');
  const [govManagerId, setGovManagerId] = useState<string>('');

  const subscriptionQuery = useQuery({
    queryKey: ['billing', 'subscription'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/billing/subscription');
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user,
  });

  const orgSettingsQuery = useQuery({
    queryKey: ['org', 'settings'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/org/settings');
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user && section === 'sites',
  });

  const sitesQuery = useQuery({
    queryKey: ['sites', 'visible'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/sites');
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user,
  });

  const membersQuery = useQuery({
    queryKey: ['org', 'members'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/org/members');
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user && section === 'staff',
  });

  const invitesQuery = useQuery({
    queryKey: ['org', 'invites'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/org/invites');
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user && section === 'staff',
  });

  const currentPlan = PROOFLY_PLANS.find((p) => p.code === subscriptionQuery.data?.planCode) ?? PROOFLY_PLANS[0];
  const activeSitesCount = (sitesQuery.data ?? []).filter((s) => s.isActive).length;
  const activeMembersCount = (membersQuery.data ?? []).filter((m) => m.status !== 'OFFBOARDED').length;

  // Create Site Form
  const siteForm = useForm<CreateSiteFormValues>({
    resolver: zodResolver(createSiteSchema),
    defaultValues: {
      name: '',
      code: '',
      address: '',
      latitude: 6.4501,
      longitude: 3.3622,
      radiusMeters: 150,
      timezone: 'Africa/Lagos',
      shiftStartLocal: '08:00',
      lateGraceMinutes: 15,
      overtimeThresholdMinutes: 480,
    },
  });

  const fillCurrentGpsForSite = () => {
    if (typeof navigator === 'undefined' || !navigator.geolocation) return;
    navigator.geolocation.getCurrentPosition((pos) => {
      siteForm.setValue('latitude', Number(pos.coords.latitude.toFixed(6)));
      siteForm.setValue('longitude', Number(pos.coords.longitude.toFixed(6)));
    });
  };

  const submitCreateSite = siteForm.handleSubmit(async (values) => {
    setPanelProblem(null);
    const { error } = await apiClient.POST('/api/v1/sites', {
      body: {
        name: values.name,
        code: values.code?.trim() || undefined,
        address: values.address?.trim() || undefined,
        latitude: values.latitude,
        longitude: values.longitude,
        radiusMeters: values.radiusMeters,
        timezone: values.timezone,
        shiftStartLocal: values.shiftStartLocal,
        lateGraceMinutes: values.lateGraceMinutes,
        overtimeThresholdMinutes: values.overtimeThresholdMinutes,
      },
    });
    if (error) {
      setPanelProblem(normalizeProblemDetail(error));
      return;
    }
    siteForm.reset({
      name: '',
      code: '',
      address: '',
      latitude: values.latitude,
      longitude: values.longitude,
      radiusMeters: 150,
      timezone: values.timezone,
      shiftStartLocal: '08:00',
      lateGraceMinutes: 15,
      overtimeThresholdMinutes: 480,
    });
    setPanelNotice('Geofenced work site created and added to PostGIS index.');
    queryClient.invalidateQueries({ queryKey: ['sites'] });
  });

  const toggleSiteMutation = useMutation({
    mutationFn: async (site: SiteView) => {
      const { error } = await apiClient.PATCH('/api/v1/sites/{siteId}', {
        params: { path: { siteId: site.id } },
        body: { isActive: !site.isActive },
      });
      if (error) throw normalizeProblemDetail(error);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sites'] });
      setPanelNotice('Site activation status updated.');
    },
    onError: (err) => setPanelProblem(normalizeProblemDetail(err)),
  });

  // Org Settings Form
  const orgForm = useForm<OrgSettingsFormValues>({
    resolver: zodResolver(orgSettingsSchema),
    defaultValues: {
      orgName: '',
      defaultTimezone: 'Africa/Lagos',
      lateGraceMinutes: 15,
      overtimeThresholdMinutes: 480,
      maxGpsAccuracyMeters: 100,
    },
  });

  useEffect(() => {
    if (orgSettingsQuery.data) {
      orgForm.reset({
        orgName: orgSettingsQuery.data.orgName,
        defaultTimezone: orgSettingsQuery.data.defaultTimezone,
        lateGraceMinutes: orgSettingsQuery.data.lateGraceMinutes,
        overtimeThresholdMinutes: orgSettingsQuery.data.overtimeThresholdMinutes,
        maxGpsAccuracyMeters: orgSettingsQuery.data.maxGpsAccuracyMeters,
      });
    }
  }, [orgSettingsQuery.data, orgForm]);

  const submitOrgSettings = orgForm.handleSubmit(async (values) => {
    setPanelProblem(null);
    const { error } = await apiClient.PUT('/api/v1/org/settings', {
      body: values,
    });
    if (error) {
      setPanelProblem(normalizeProblemDetail(error));
      return;
    }
    setPanelNotice('Organization attendance defaults saved.');
    queryClient.invalidateQueries({ queryKey: ['org', 'settings'] });
    queryClient.invalidateQueries({ queryKey: ['tenant', 'me'] });
  });

  // Staff Invite Form
  const inviteForm = useForm<InviteStaffFormValues>({
    resolver: zodResolver(inviteStaffSchema),
    defaultValues: {
      inviteType: 'EMAIL',
      fullName: '',
      email: '',
      phoneE164: '',
      initialPin: '',
      role: 'EMPLOYEE',
      managerMembershipId: '',
      jobTitle: '',
      employeeCode: '',
    },
  });

  const watchedInviteType = inviteForm.watch('inviteType');

  const submitInvite = inviteForm.handleSubmit(async (values) => {
    setPanelProblem(null);
    const { error } = await apiClient.POST('/api/v1/org/invites', {
      body: {
        inviteType: values.inviteType,
        fullName: values.fullName,
        email: values.inviteType === 'EMAIL' ? values.email : undefined,
        phoneE164: values.phoneE164?.trim() || undefined,
        initialPin: values.inviteType === 'PHONE_PIN' ? values.initialPin : undefined,
        role: values.role,
        managerMembershipId: values.managerMembershipId?.trim() || null,
        jobTitle: values.jobTitle?.trim() || undefined,
        employeeCode: values.employeeCode?.trim() || undefined,
      },
    });
    if (error) {
      setPanelProblem(normalizeProblemDetail(error));
      return;
    }
    inviteForm.reset({
      inviteType: values.inviteType,
      fullName: '',
      email: '',
      phoneE164: '',
      initialPin: '',
      role: 'EMPLOYEE',
      managerMembershipId: '',
      jobTitle: '',
      employeeCode: '',
    });
    setPanelNotice(
      values.inviteType === 'PHONE_PIN'
        ? 'Field staff account created and ready for Phone + PIN sign-in.'
        : 'Work email invitation queued in Notification Outbox.'
    );
    queryClient.invalidateQueries({ queryKey: ['org', 'members'] });
    queryClient.invalidateQueries({ queryKey: ['org', 'invites'] });
  });

  const openAssignmentDrawer = (member: MemberSummary) => {
    setGovTarget(null);
    setAssignTarget(member);
    const draft: Record<string, AssignmentDraftRow> = {};
    (sitesQuery.data ?? []).forEach((site, idx) => {
      const assigned = member.assignedSiteIds.includes(site.id);
      draft[site.id] = {
        selected: assigned,
        isPrimary: assigned && member.assignedSiteIds[0] === site.id,
        shiftStartOverride: '',
        lateGraceOverride: '',
        overtimeThresholdOverride: '',
      };
      if (!assigned && member.assignedSiteIds.length === 0 && idx === 0) {
        draft[site.id].isPrimary = false;
      }
    });
    setAssignDraft(draft);
  };

  const saveAssignmentsMutation = useMutation({
    mutationFn: async () => {
      if (!assignTarget) return;
      const items: SiteAssignmentItem[] = Object.entries(assignDraft)
        .filter(([, row]) => row.selected)
        .map(([siteId, row]) => ({
          siteId,
          isPrimary: row.isPrimary,
          shiftStartOverride: row.shiftStartOverride.trim() || null,
          lateGraceOverride: row.lateGraceOverride.trim() ? Number(row.lateGraceOverride) : null,
          overtimeThresholdOverride: row.overtimeThresholdOverride.trim()
            ? Number(row.overtimeThresholdOverride)
            : null,
        }));
      const { error } = await apiClient.PUT('/api/v1/sites/assignments/{membershipId}', {
        params: { path: { membershipId: assignTarget.membershipId } },
        body: { assignments: items },
      });
      if (error) throw normalizeProblemDetail(error);
    },
    onSuccess: () => {
      setAssignTarget(null);
      setPanelNotice('Multi-site assignments and per-site shift overrides updated.');
      queryClient.invalidateQueries({ queryKey: ['org', 'members'] });
      queryClient.invalidateQueries({ queryKey: ['sites'] });
    },
    onError: (err) => setPanelProblem(normalizeProblemDetail(err)),
  });

  const openGovernanceDrawer = (member: MemberSummary) => {
    setAssignTarget(null);
    setGovTarget(member);
    setGovRole(member.role);
    setGovStatus((member.status as 'ACTIVE' | 'SUSPENDED' | 'OFFBOARDED') || 'ACTIVE');
    setGovManagerId(member.managerMembershipId ?? '');
  };

  const saveMemberGovMutation = useMutation({
    mutationFn: async () => {
      if (!govTarget) return;
      const { error } = await apiClient.PATCH('/api/v1/org/members/{membershipId}', {
        params: { path: { membershipId: govTarget.membershipId } },
        body: {
          role: govRole,
          status: govStatus,
          managerMembershipId: govManagerId || null,
        },
      });
      if (error) throw normalizeProblemDetail(error);
    },
    onSuccess: () => {
      setGovTarget(null);
      setPanelNotice('Member role, manager, and status updated in control + tenant records.');
      queryClient.invalidateQueries({ queryKey: ['org', 'members'] });
    },
    onError: (err) => setPanelProblem(normalizeProblemDetail(err)),
  });

  const managersAndAdmins = (membersQuery.data ?? []).filter(
    (m) => m.status === 'ACTIVE' && (m.role === 'MANAGER' || m.role === 'ADMIN' || m.role === 'SUPER_ADMIN')
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

      {section === 'sites' && (
        <>
          <div className="grid grid-cols-1 gap-6 lg:grid-cols-12">
            {/* Create Geofenced Site */}
            <Card className="p-6 lg:col-span-7">
              <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 pb-4">
                <div>
                  <h2 className="font-display text-base font-bold text-[#0B1F3A]">Create Geofenced Work Site</h2>
                  <p className="mt-0.5 font-mono text-xs tabular-nums text-[#526277]">
                    Active sites: {activeSitesCount} / {currentPlan.maxSites} ({currentPlan.name} plan)
                  </p>
                </div>
                <Button variant="outline" size="sm" onClick={fillCurrentGpsForSite}>
                  Use my GPS
                </Button>
              </div>

              <form onSubmit={submitCreateSite} className="mt-5 space-y-4" noValidate>
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                  <div className="sm:col-span-2">
                    <Label htmlFor="site-name">Site name</Label>
                    <Input id="site-name" placeholder="Apapa Gate Terminal" {...siteForm.register('name')} />
                    {siteForm.formState.errors.name && (
                      <p className="mt-1 text-xs text-red-700">{siteForm.formState.errors.name.message}</p>
                    )}
                  </div>
                  <div>
                    <Label htmlFor="site-code">Site code (optional)</Label>
                    <Input id="site-code" placeholder="APAPA-01" className="font-mono text-xs" {...siteForm.register('code')} />
                  </div>
                </div>

                <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                  <div>
                    <Label htmlFor="site-lat">Latitude</Label>
                    <Input id="site-lat" type="number" step="0.000001" className="font-mono text-xs tabular-nums" {...siteForm.register('latitude', { valueAsNumber: true })} />
                  </div>
                  <div>
                    <Label htmlFor="site-lng">Longitude</Label>
                    <Input id="site-lng" type="number" step="0.000001" className="font-mono text-xs tabular-nums" {...siteForm.register('longitude', { valueAsNumber: true })} />
                  </div>
                  <div>
                    <Label htmlFor="site-radius">Radius (meters)</Label>
                    <Input id="site-radius" type="number" className="font-mono text-xs tabular-nums" {...siteForm.register('radiusMeters', { valueAsNumber: true })} />
                  </div>
                </div>

                <div className="grid grid-cols-1 gap-4 sm:grid-cols-4">
                  <div>
                    <Label htmlFor="site-tz">Timezone</Label>
                    <Input id="site-tz" className="font-mono text-xs" {...siteForm.register('timezone')} />
                  </div>
                  <div>
                    <Label htmlFor="site-shift">Shift start</Label>
                    <Input id="site-shift" placeholder="08:00" className="font-mono text-xs tabular-nums" {...siteForm.register('shiftStartLocal')} />
                  </div>
                  <div>
                    <Label htmlFor="site-grace">Late grace (m)</Label>
                    <Input id="site-grace" type="number" className="font-mono text-xs tabular-nums" {...siteForm.register('lateGraceMinutes', { valueAsNumber: true })} />
                  </div>
                  <div>
                    <Label htmlFor="site-ot">Overtime after (m)</Label>
                    <Input id="site-ot" type="number" className="font-mono text-xs tabular-nums" {...siteForm.register('overtimeThresholdMinutes', { valueAsNumber: true })} />
                  </div>
                </div>

                <div>
                  <Label htmlFor="site-address">Street address (optional)</Label>
                  <Input id="site-address" placeholder="Wharf Road, Apapa, Lagos" {...siteForm.register('address')} />
                </div>

                <Button type="submit" variant="primary" size="md" disabled={siteForm.formState.isSubmitting}>
                  {siteForm.formState.isSubmitting ? 'Creating site...' : 'Create Geofenced Site'}
                </Button>
              </form>
            </Card>

            {/* Organization Rules */}
            <Card className="p-6 lg:col-span-5">
              <div className="border-b border-slate-200 pb-4">
                <h2 className="font-display text-base font-bold text-[#0B1F3A]">Organization Attendance Rules</h2>
                <p className="mt-0.5 text-xs text-[#526277]">
                  Applied as defaults for new sites and GPS accuracy verification across all clock-ins.
                </p>
              </div>

              <form onSubmit={submitOrgSettings} className="mt-5 space-y-4" noValidate>
                <div>
                  <Label htmlFor="org-name">Organization display name</Label>
                  <Input id="org-name" {...orgForm.register('orgName')} />
                </div>
                <div>
                  <Label htmlFor="org-tz">Default IANA timezone</Label>
                  <Input id="org-tz" className="font-mono text-xs" {...orgForm.register('defaultTimezone')} />
                </div>
                <div className="grid grid-cols-3 gap-3">
                  <div>
                    <Label htmlFor="org-grace">Late grace (m)</Label>
                    <Input id="org-grace" type="number" className="font-mono text-xs tabular-nums" {...orgForm.register('lateGraceMinutes', { valueAsNumber: true })} />
                  </div>
                  <div>
                    <Label htmlFor="org-ot">OT (mins)</Label>
                    <Input id="org-ot" type="number" className="font-mono text-xs tabular-nums" {...orgForm.register('overtimeThresholdMinutes', { valueAsNumber: true })} />
                  </div>
                  <div>
                    <Label htmlFor="org-gps">Max GPS (m)</Label>
                    <Input id="org-gps" type="number" className="font-mono text-xs tabular-nums" {...orgForm.register('maxGpsAccuracyMeters', { valueAsNumber: true })} />
                  </div>
                </div>
                <Button type="submit" variant="secondary" size="md" disabled={orgForm.formState.isSubmitting}>
                  {orgForm.formState.isSubmitting ? 'Saving...' : 'Save Organization Rules'}
                </Button>
              </form>
            </Card>
          </div>

          {/* Sites Table */}
          <Card className="p-6">
            <h2 className="font-display text-base font-bold text-[#0B1F3A]">Configured Work Sites</h2>
            {sitesQuery.isLoading && <p className="mt-3 text-sm text-[#526277]">Loading sites...</p>}
            {!sitesQuery.isLoading && (sitesQuery.data ?? []).length === 0 && (
              <p className="mt-3 text-sm text-[#526277]">No sites created yet. Add your first geofenced work site above.</p>
            )}
            {(sitesQuery.data ?? []).length > 0 && (
              <div className="mt-4 overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead>
                    <tr className="border-b border-slate-200 text-xs text-[#526277]">
                      <th className="py-2 pr-4 font-semibold">Site</th>
                      <th className="py-2 pr-4 font-semibold">Coordinates</th>
                      <th className="py-2 pr-4 font-semibold">Radius</th>
                      <th className="py-2 pr-4 font-semibold">Shift &amp; Rules</th>
                      <th className="py-2 pr-4 font-semibold">Assigned Staff</th>
                      <th className="py-2 pr-4 font-semibold">Status</th>
                      <th className="py-2 text-right font-semibold">Action</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {(sitesQuery.data ?? []).map((site) => (
                      <tr key={site.id}>
                        <td className="py-3 pr-4">
                          <span className="font-medium text-[#0B1F3A]">{site.name}</span>
                          <span className="ml-2 font-mono text-xs text-[#526277]">{site.code}</span>
                        </td>
                        <td className="py-3 pr-4 font-mono text-xs tabular-nums text-[#526277]">
                          {site.latitude.toFixed(4)}, {site.longitude.toFixed(4)}
                        </td>
                        <td className="py-3 pr-4 font-mono text-xs tabular-nums text-[#0B1F3A]">
                          {site.radiusMeters}m
                        </td>
                        <td className="py-3 pr-4 font-mono text-xs tabular-nums text-[#526277]">
                          {site.shiftStartLocal} · +{site.lateGraceMinutes}m · {site.timezone}
                        </td>
                        <td className="py-3 pr-4 font-mono text-xs tabular-nums text-[#0B1F3A]">
                          {site.assignedStaffCount}
                        </td>
                        <td className="py-3 pr-4 font-mono text-xs font-semibold">
                          <span className={site.isActive ? 'text-[#09A55A]' : 'text-slate-400'}>
                            {site.isActive ? 'ACTIVE' : 'INACTIVE'}
                          </span>
                        </td>
                        <td className="py-3 text-right">
                          <Button
                            variant="outline"
                            size="sm"
                            disabled={toggleSiteMutation.isPending}
                            onClick={() => toggleSiteMutation.mutate(site)}
                          >
                            {site.isActive ? 'Deactivate' : 'Activate'}
                          </Button>
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

      {section === 'staff' && (
        <>
          {/* Invite Staff Card */}
          <Card className="p-6">
            <div className="flex flex-wrap items-center justify-between gap-4 border-b border-slate-200 pb-4">
              <div>
                <h2 className="font-display text-base font-bold text-[#0B1F3A]">Invite or Onboard Staff</h2>
                <p className="mt-0.5 font-mono text-xs tabular-nums text-[#526277]">
                  Seats used: {activeMembersCount} / {currentPlan.maxMembers} ({currentPlan.name} plan)
                </p>
              </div>
              <div className="flex gap-2" role="tablist" aria-label="Invite method">
                <button
                  type="button"
                  role="tab"
                  aria-selected={watchedInviteType === 'EMAIL'}
                  onClick={() => inviteForm.setValue('inviteType', 'EMAIL')}
                  className={`min-h-[40px] rounded-lg px-3.5 py-2 text-xs font-semibold cursor-pointer ${
                    watchedInviteType === 'EMAIL' ? 'bg-[#0B1F3A] text-white' : 'border border-slate-200 text-[#526277]'
                  }`}
                >
                  Work Email Invite
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={watchedInviteType === 'PHONE_PIN'}
                  onClick={() => inviteForm.setValue('inviteType', 'PHONE_PIN')}
                  className={`min-h-[40px] rounded-lg px-3.5 py-2 text-xs font-semibold cursor-pointer ${
                    watchedInviteType === 'PHONE_PIN'
                      ? 'bg-[#0B1F3A] text-white'
                      : 'border border-slate-200 text-[#526277]'
                  }`}
                >
                  Phone + PIN (Field Staff)
                </button>
              </div>
            </div>

            <form onSubmit={submitInvite} className="mt-5 grid grid-cols-1 gap-4 sm:grid-cols-4" noValidate>
              <div>
                <Label htmlFor="invite-name">Full name</Label>
                <Input id="invite-name" placeholder="Bala Musa" {...inviteForm.register('fullName')} />
                {inviteForm.formState.errors.fullName && (
                  <p className="mt-1 text-xs text-red-700">{inviteForm.formState.errors.fullName.message}</p>
                )}
              </div>

              {watchedInviteType === 'EMAIL' ? (
                <div>
                  <Label htmlFor="invite-email">Work email</Label>
                  <Input id="invite-email" type="email" placeholder="bala@company.ng" {...inviteForm.register('email')} />
                  {inviteForm.formState.errors.email && (
                    <p className="mt-1 text-xs text-red-700">{inviteForm.formState.errors.email.message}</p>
                  )}
                </div>
              ) : (
                <>
                  <div>
                    <Label htmlFor="invite-phone">Phone (E.164)</Label>
                    <Input id="invite-phone" placeholder="+2348011122233" className="font-mono text-xs" {...inviteForm.register('phoneE164')} />
                    {inviteForm.formState.errors.phoneE164 && (
                      <p className="mt-1 text-xs text-red-700">{inviteForm.formState.errors.phoneE164.message}</p>
                    )}
                  </div>
                  <div>
                    <Label htmlFor="invite-pin">Initial PIN (4–8 digits)</Label>
                    <Input id="invite-pin" inputMode="numeric" placeholder="4829" className="font-mono text-xs tabular-nums" {...inviteForm.register('initialPin')} />
                    {inviteForm.formState.errors.initialPin && (
                      <p className="mt-1 text-xs text-red-700">{inviteForm.formState.errors.initialPin.message}</p>
                    )}
                  </div>
                </>
              )}

              <div>
                <Label htmlFor="invite-role">Role</Label>
                <select
                  id="invite-role"
                  className="w-full min-h-[44px] rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-[#0B1F3A]"
                  {...inviteForm.register('role')}
                >
                  <option value="EMPLOYEE">EMPLOYEE (Personal shift &amp; leave)</option>
                  <option value="MANAGER">MANAGER (Team board &amp; approvals)</option>
                  {user?.role === 'SUPER_ADMIN' && (
                    <option value="ADMIN">ADMIN (Org operations &amp; billing)</option>
                  )}
                </select>
              </div>

              <div>
                <Label htmlFor="invite-manager">Assigned manager</Label>
                <select
                  id="invite-manager"
                  className="w-full min-h-[44px] rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-[#0B1F3A]"
                  {...inviteForm.register('managerMembershipId')}
                >
                  <option value="">No manager (top-level)</option>
                  {managersAndAdmins.map((mgr) => (
                    <option key={mgr.membershipId} value={mgr.membershipId}>
                      {mgr.fullName} ({mgr.role})
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <Label htmlFor="invite-title">Job title (optional)</Label>
                <Input id="invite-title" placeholder="Fleet Supervisor" {...inviteForm.register('jobTitle')} />
              </div>

              <div>
                <Label htmlFor="invite-emp-code">Staff ID (optional)</Label>
                <Input id="invite-emp-code" placeholder="EMP-042" className="font-mono text-xs" {...inviteForm.register('employeeCode')} />
              </div>

              <div className="flex items-end">
                <Button
                  type="submit"
                  variant="primary"
                  size="md"
                  className="w-full"
                  disabled={inviteForm.formState.isSubmitting}
                >
                  {inviteForm.formState.isSubmitting
                    ? 'Saving...'
                    : watchedInviteType === 'PHONE_PIN'
                      ? 'Activate Staff Now'
                      : 'Send Email Invite'}
                </Button>
              </div>
            </form>
          </Card>

          {/* Multi-site Assignment Drawer */}
          {assignTarget && (
            <Card className="p-6">
              <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 pb-4">
                <div>
                  <h3 className="font-display text-base font-bold text-[#0B1F3A]">
                    Assign Sites &amp; Per-Site Shift Rules — {assignTarget.fullName}
                  </h3>
                  <p className="text-xs text-[#526277]">
                    Staff can belong to multiple sites with optional shift start, grace, or overtime overrides per site.
                  </p>
                </div>
                <Button variant="outline" size="sm" onClick={() => setAssignTarget(null)}>
                  Cancel
                </Button>
              </div>

              <div className="mt-4 space-y-3">
                {(sitesQuery.data ?? []).map((site) => {
                  const row = assignDraft[site.id] ?? {
                    selected: false,
                    isPrimary: false,
                    shiftStartOverride: '',
                    lateGraceOverride: '',
                    overtimeThresholdOverride: '',
                  };
                  return (
                    <div key={site.id} className="grid grid-cols-1 gap-3 rounded-lg border border-slate-200 p-3 sm:grid-cols-12 sm:items-center">
                      <label className="flex items-center gap-2 sm:col-span-4 text-sm font-medium text-[#0B1F3A]">
                        <input
                          type="checkbox"
                          checked={row.selected}
                          onChange={(e) =>
                            setAssignDraft((prev) => ({
                              ...prev,
                              [site.id]: { ...row, selected: e.target.checked },
                            }))
                          }
                        />
                        <span>
                          {site.name} ({site.code})
                        </span>
                      </label>
                      <label className="flex items-center gap-2 sm:col-span-2 text-xs text-[#526277]">
                        <input
                          type="radio"
                          name="primary-site"
                          disabled={!row.selected}
                          checked={row.isPrimary}
                          onChange={() =>
                            setAssignDraft((prev) => {
                              const next = { ...prev };
                              Object.keys(next).forEach((k) => {
                                next[k] = { ...next[k], isPrimary: k === site.id };
                              });
                              return next;
                            })
                          }
                        />
                        Primary site
                      </label>
                      <Input
                        aria-label={`Shift start override for ${site.name}`}
                        disabled={!row.selected}
                        placeholder={`Start (${site.shiftStartLocal})`}
                        value={row.shiftStartOverride}
                        onChange={(e) =>
                          setAssignDraft((prev) => ({
                            ...prev,
                            [site.id]: { ...row, shiftStartOverride: e.target.value },
                          }))
                        }
                        className="sm:col-span-2 font-mono text-xs"
                      />
                      <Input
                        aria-label={`Late grace override for ${site.name}`}
                        disabled={!row.selected}
                        placeholder={`Grace (${site.lateGraceMinutes}m)`}
                        value={row.lateGraceOverride}
                        onChange={(e) =>
                          setAssignDraft((prev) => ({
                            ...prev,
                            [site.id]: { ...row, lateGraceOverride: e.target.value },
                          }))
                        }
                        className="sm:col-span-2 font-mono text-xs tabular-nums"
                      />
                      <Input
                        aria-label={`Overtime override for ${site.name}`}
                        disabled={!row.selected}
                        placeholder={`OT (${site.overtimeThresholdMinutes}m)`}
                        value={row.overtimeThresholdOverride}
                        onChange={(e) =>
                          setAssignDraft((prev) => ({
                            ...prev,
                            [site.id]: { ...row, overtimeThresholdOverride: e.target.value },
                          }))
                        }
                        className="sm:col-span-2 font-mono text-xs tabular-nums"
                      />
                    </div>
                  );
                })}
              </div>

              <div className="mt-4 flex justify-end">
                <Button
                  variant="primary"
                  size="md"
                  onClick={() => saveAssignmentsMutation.mutate()}
                  disabled={saveAssignmentsMutation.isPending}
                >
                  {saveAssignmentsMutation.isPending ? 'Saving...' : 'Save Site Assignments'}
                </Button>
              </div>
            </Card>
          )}

          {/* Role & Governance Drawer */}
          {govTarget && (
            <Card className="p-6">
              <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 pb-4">
                <div>
                  <h3 className="font-display text-base font-bold text-[#0B1F3A]">
                    Role &amp; Status Governance — {govTarget.fullName}
                  </h3>
                  <p className="text-xs text-[#526277]">
                    Changes are audited in tenant_audit_log and mirrored immediately to the control-plane directory.
                  </p>
                </div>
                <Button variant="outline" size="sm" onClick={() => setGovTarget(null)}>
                  Cancel
                </Button>
              </div>

              <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-4">
                <div>
                  <Label htmlFor="gov-role">Role</Label>
                  <select
                    id="gov-role"
                    value={govRole}
                    onChange={(e) => setGovRole(e.target.value as Role)}
                    className="w-full min-h-[44px] rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-[#0B1F3A]"
                  >
                    <option value="EMPLOYEE">EMPLOYEE</option>
                    <option value="MANAGER">MANAGER</option>
                    {user?.role === 'SUPER_ADMIN' && (
                      <>
                        <option value="ADMIN">ADMIN</option>
                        <option value="SUPER_ADMIN">SUPER_ADMIN</option>
                      </>
                    )}
                  </select>
                </div>
                <div>
                  <Label htmlFor="gov-status">Status</Label>
                  <select
                    id="gov-status"
                    value={govStatus}
                    onChange={(e) => setGovStatus(e.target.value as 'ACTIVE' | 'SUSPENDED' | 'OFFBOARDED')}
                    className="w-full min-h-[44px] rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-[#0B1F3A]"
                  >
                    <option value="ACTIVE">ACTIVE</option>
                    <option value="SUSPENDED">SUSPENDED</option>
                    <option value="OFFBOARDED">OFFBOARDED</option>
                  </select>
                </div>
                <div>
                  <Label htmlFor="gov-manager">Manager</Label>
                  <select
                    id="gov-manager"
                    value={govManagerId}
                    onChange={(e) => setGovManagerId(e.target.value)}
                    className="w-full min-h-[44px] rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-[#0B1F3A]"
                  >
                    <option value="">None</option>
                    {managersAndAdmins
                      .filter((m) => m.membershipId !== govTarget.membershipId)
                      .map((m) => (
                        <option key={m.membershipId} value={m.membershipId}>
                          {m.fullName}
                        </option>
                      ))}
                  </select>
                </div>
                <div className="flex items-end">
                  <Button
                    variant="primary"
                    size="md"
                    className="w-full"
                    disabled={
                      saveMemberGovMutation.isPending ||
                      !canEditMemberRole(user?.role ?? 'EMPLOYEE', govTarget.role, govRole)
                    }
                    onClick={() => saveMemberGovMutation.mutate()}
                  >
                    {saveMemberGovMutation.isPending ? 'Saving...' : 'Save Changes'}
                  </Button>
                </div>
              </div>
            </Card>
          )}

          {/* Staff Roster Table */}
          <Card className="p-6">
            <h2 className="font-display text-base font-bold text-[#0B1F3A]">Organization Staff Roster</h2>
            {membersQuery.isLoading && <p className="mt-3 text-sm text-[#526277]">Loading roster...</p>}
            {(membersQuery.data ?? []).length > 0 && (
              <div className="mt-4 overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead>
                    <tr className="border-b border-slate-200 text-xs text-[#526277]">
                      <th className="py-2 pr-4 font-semibold">Staff</th>
                      <th className="py-2 pr-4 font-semibold">Identity</th>
                      <th className="py-2 pr-4 font-semibold">Role</th>
                      <th className="py-2 pr-4 font-semibold">Manager</th>
                      <th className="py-2 pr-4 font-semibold">Sites</th>
                      <th className="py-2 pr-4 font-semibold">Status</th>
                      <th className="py-2 text-right font-semibold">Actions</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {(membersQuery.data ?? []).map((member) => {
                      const canGovern = canEditMemberRole(user?.role ?? 'EMPLOYEE', member.role);
                      return (
                        <tr key={member.membershipId}>
                          <td className="py-3 pr-4">
                            <span className="font-medium text-[#0B1F3A]">{member.fullName}</span>
                            {member.jobTitle && (
                              <span className="block text-xs text-[#526277]">{member.jobTitle}</span>
                            )}
                          </td>
                          <td className="py-3 pr-4 font-mono text-xs text-[#526277]">
                            {member.email ?? member.phoneE164}
                          </td>
                          <td className="py-3 pr-4 font-mono text-xs font-semibold text-[#0B1F3A]">{member.role}</td>
                          <td className="py-3 pr-4 text-xs text-[#526277]">{member.managerName ?? '—'}</td>
                          <td className="py-3 pr-4 font-mono text-xs tabular-nums text-[#0B1F3A]">
                            {member.assignedSiteIds.length}
                          </td>
                          <td className="py-3 pr-4 font-mono text-xs font-semibold">
                            <span className={member.status === 'ACTIVE' ? 'text-[#09A55A]' : 'text-red-700'}>
                              {member.status}
                            </span>
                          </td>
                          <td className="py-3 text-right space-x-2 whitespace-nowrap">
                            <Button variant="outline" size="sm" onClick={() => openAssignmentDrawer(member)}>
                              Sites
                            </Button>
                            {canGovern && (
                              <Button variant="outline" size="sm" onClick={() => openGovernanceDrawer(member)}>
                                Role
                              </Button>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}

            {(invitesQuery.data ?? []).length > 0 && (
              <div className="mt-6 border-t border-slate-200 pt-4">
                <h3 className="text-xs font-semibold text-[#0B1F3A]">Recent Email Invitations</h3>
                <ul className="mt-2 divide-y divide-slate-100 text-xs">
                  {(invitesQuery.data ?? []).slice(0, 8).map((inv) => (
                    <li key={inv.id} className="flex flex-wrap items-center justify-between gap-2 py-2">
                      <span className="font-mono text-[#0B1F3A]">
                        {inv.email} · {inv.role}
                      </span>
                      <span className={`font-mono font-semibold ${inv.acceptedAt ? 'text-[#09A55A]' : 'text-amber-700'}`}>
                        {inv.acceptedAt ? `Accepted ${new Date(inv.acceptedAt).toLocaleDateString()}` : 'Pending email acceptance'}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </Card>
        </>
      )}
    </div>
  );
};
