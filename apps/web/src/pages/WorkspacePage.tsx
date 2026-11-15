import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Button, Card, Input, Label } from '@proofly/ui';
import { normalizeProblemDetail, type MfaSetupResponse, type ProblemDetail } from '@proofly/api-client';
import { useAuth } from '../lib/authStore';
import { mfaEnableSchema, type MfaEnableFormValues } from '../lib/validation';

export const WorkspacePage: React.FC = () => {
  const { user, deviceId, apiClient, logout } = useAuth();
  const queryClient = useQueryClient();
  const [mfaSetup, setMfaSetup] = useState<MfaSetupResponse | null>(null);
  const [actionProblem, setActionProblem] = useState<ProblemDetail | null>(null);
  const [mfaSuccess, setMfaSuccess] = useState(false);

  const tenantQuery = useQuery({
    queryKey: ['tenant', 'me'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/tenants/me');
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
  });

  const devicesQuery = useQuery({
    queryKey: ['devices'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/devices');
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
  });

  const revokeMutation = useMutation({
    mutationFn: async (targetDeviceId: string) => {
      const { error } = await apiClient.DELETE('/api/v1/devices/{deviceId}', {
        params: { path: { deviceId: targetDeviceId } },
      });
      if (error) throw normalizeProblemDetail(error);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['devices'] });
    },
    onError: (err) => setActionProblem(normalizeProblemDetail(err)),
  });

  const mfaForm = useForm<MfaEnableFormValues>({
    resolver: zodResolver(mfaEnableSchema),
    defaultValues: { totpCode: '' },
  });

  const startMfaSetup = async () => {
    setActionProblem(null);
    const { data, error } = await apiClient.POST('/api/v1/auth/mfa/setup');
    if (error || !data) {
      setActionProblem(normalizeProblemDetail(error));
      return;
    }
    setMfaSetup(data);
  };

  const submitMfaEnable = mfaForm.handleSubmit(async (values) => {
    setActionProblem(null);
    const { error } = await apiClient.POST('/api/v1/auth/mfa/enable', {
      body: { totpCode: values.totpCode },
    });
    if (error) {
      setActionProblem(normalizeProblemDetail(error));
      return;
    }
    setMfaSuccess(true);
    setMfaSetup(null);
  });

  if (!user) return null;

  const isAdmin = user.role === 'SUPER_ADMIN' || user.role === 'ADMIN';

  return (
    <div className="mx-auto max-w-6xl space-y-8 px-4 py-8 sm:px-6">
      <div className="flex flex-wrap items-center justify-between gap-4 border-b border-slate-200 pb-6">
        <div>
          <h1 className="font-display text-2xl font-bold text-[#0B1F3A]">
            {tenantQuery.data?.name ?? 'Workspace Security & Tenancy'}
          </h1>
          <p className="mt-1 text-sm text-[#526277]">
            {user.fullName} · {user.role} · {user.email ?? user.phoneE164}
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={logout}>
          Sign out
        </Button>
      </div>

      {actionProblem && (
        <Alert
          tone="error"
          title={actionProblem.title}
          description={actionProblem.detail}
          meta={`${actionProblem.errorCode} · corrId ${actionProblem.correlationId}`}
        />
      )}

      {mfaSuccess && (
        <Alert
          tone="success"
          title="Two-factor authentication enabled"
          description="Your TOTP secret is encrypted at rest and will be required on future sign-ins."
        />
      )}

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-12">
        <Card className="p-6 lg:col-span-5">
          <h2 className="font-display text-base font-bold text-[#0B1F3A]">Tenant Isolation Profile</h2>
          {tenantQuery.isLoading && <p className="mt-3 text-sm text-[#526277]">Loading tenant profile...</p>}
          {tenantQuery.error && (
            <div className="mt-3">
              <Alert tone="error" title="Failed to load tenant" description={normalizeProblemDetail(tenantQuery.error).detail} />
            </div>
          )}
          {tenantQuery.data && (
            <dl className="mt-4 divide-y divide-slate-100 text-sm">
              <div className="flex justify-between py-2.5">
                <dt className="text-[#526277]">Workspace slug</dt>
                <dd className="font-mono font-medium text-[#0B1F3A]">{tenantQuery.data.slug}</dd>
              </div>
              <div className="flex justify-between py-2.5">
                <dt className="text-[#526277]">Status</dt>
                <dd className="font-medium text-[#09A55A]">{tenantQuery.data.status}</dd>
              </div>
              <div className="flex justify-between py-2.5">
                <dt className="text-[#526277]">Schema version</dt>
                <dd className="font-mono tabular-nums text-[#0B1F3A]">v{tenantQuery.data.schemaVersion}</dd>
              </div>
              <div className="flex justify-between py-2.5">
                <dt className="text-[#526277]">Primary currency</dt>
                <dd className="font-mono text-[#0B1F3A]">{tenantQuery.data.defaultCurrency}</dd>
              </div>
            </dl>
          )}
        </Card>

        <Card className="p-6 lg:col-span-7">
          <div className="flex items-center justify-between gap-4">
            <h2 className="font-display text-base font-bold text-[#0B1F3A]">Registered Devices</h2>
            <span className="text-xs text-[#526277] tabular-nums">
              {devicesQuery.data?.length ?? 0} total
            </span>
          </div>

          {devicesQuery.isLoading && <p className="mt-4 text-sm text-[#526277]">Loading devices...</p>}
          {devicesQuery.data && devicesQuery.data.length === 0 && (
            <p className="mt-4 text-sm text-[#526277]">No registered devices found.</p>
          )}
          {devicesQuery.data && devicesQuery.data.length > 0 && (
            <div className="mt-4 overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-slate-200 text-xs text-[#526277]">
                    <th className="py-2 pr-4 font-semibold">Device</th>
                    <th className="py-2 pr-4 font-semibold">Platform</th>
                    <th className="py-2 pr-4 font-semibold">Last seen</th>
                    <th className="py-2 text-right font-semibold">Action</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {devicesQuery.data.map((dev) => {
                    const isCurrent = dev.id === deviceId;
                    const isRevoked = !!dev.revokedAt;
                    return (
                      <tr key={dev.id}>
                        <td className="py-3 pr-4">
                          <span className="font-medium text-[#0B1F3A]">
                            {dev.deviceName.slice(0, 32)}
                          </span>
                          {isCurrent && <span className="ml-2 text-xs text-[#09A55A]">Current</span>}
                        </td>
                        <td className="py-3 pr-4 font-mono text-xs text-[#526277]">{dev.platform}</td>
                        <td className="py-3 pr-4 font-mono text-xs tabular-nums text-[#526277]">
                          {new Date(dev.lastSeenAt).toLocaleString()}
                        </td>
                        <td className="py-3 text-right">
                          {isRevoked ? (
                            <span className="text-xs text-slate-400">Revoked</span>
                          ) : (
                            <Button
                              variant="outline"
                              size="sm"
                              onClick={() => revokeMutation.mutate(dev.id)}
                              disabled={revokeMutation.isPending}
                            >
                              Revoke
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
        </Card>
      </div>

      {isAdmin && (
        <Card className="p-6">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div>
              <h2 className="font-display text-base font-bold text-[#0B1F3A]">
                Administrator MFA (TOTP)
              </h2>
              <p className="mt-1 text-sm text-[#526277]">
                Protect SUPER_ADMIN and ADMIN operations with an authenticator app.
              </p>
            </div>
            {!user.mfaEnabled && !mfaSetup && (
              <Button variant="secondary" size="sm" onClick={startMfaSetup}>
                Configure TOTP
              </Button>
            )}
          </div>

          {mfaSetup && (
            <form onSubmit={submitMfaEnable} className="mt-5 max-w-md space-y-4 border-t border-slate-200 pt-5">
              <div>
                <Label htmlFor="mfa-secret-display">Base32 Secret Key</Label>
                <input
                  id="mfa-secret-display"
                  readOnly
                  value={mfaSetup.base32Secret}
                  className="w-full rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 font-mono text-xs text-[#0B1F3A]"
                />
              </div>
              <div>
                <Label htmlFor="mfa-totp-code">Enter 6-digit verification code</Label>
                <Input
                  id="mfa-totp-code"
                  inputMode="numeric"
                  maxLength={6}
                  placeholder="123456"
                  className="font-mono tabular-nums"
                  {...mfaForm.register('totpCode')}
                />
                {mfaForm.formState.errors.totpCode && (
                  <p className="mt-1 text-xs text-red-700">{mfaForm.formState.errors.totpCode.message}</p>
                )}
              </div>
              <Button type="submit" variant="primary" size="sm">
                Verify &amp; Enable MFA
              </Button>
            </form>
          )}
        </Card>
      )}
    </div>
  );
};
