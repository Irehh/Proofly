import React, { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Button, Card, Input, Label } from '@proofly/ui';
import { normalizeProblemDetail, type ProblemDetail } from '@proofly/api-client';
import { useAuth } from '../lib/authStore';
import { acceptInviteSchema, type AcceptInviteFormValues } from '../lib/validation';

export const AcceptInvitePage: React.FC = () => {
  const { apiClient } = useAuth();
  const [searchParams] = useSearchParams();
  const [problem, setProblem] = useState<ProblemDetail | null>(null);
  const [accepted, setAccepted] = useState(false);

  const hasQueryLink = Boolean(searchParams.get('tid') && searchParams.get('token'));

  const form = useForm<AcceptInviteFormValues>({
    resolver: zodResolver(acceptInviteSchema),
    defaultValues: {
      tenantId: searchParams.get('tid') ?? '',
      token: searchParams.get('token') ?? '',
      fullName: '',
      password: '',
      confirmPassword: '',
    },
  });

  const onSubmit = form.handleSubmit(async (values) => {
    setProblem(null);
    const { error } = await apiClient.POST('/api/v1/org/invites/accept', {
      body: {
        tenantId: values.tenantId,
        token: values.token,
        fullName: values.fullName?.trim() || undefined,
        password: values.password,
      },
    });
    if (error) {
      setProblem(normalizeProblemDetail(error));
      return;
    }
    setAccepted(true);
  });

  return (
    <div className="mx-auto max-w-md px-4 py-12 sm:px-6 lg:py-16">
      <Card className="p-6 sm:p-8">
        <div className="border-b border-slate-200 pb-5">
          <h1 className="font-display text-2xl font-bold tracking-tight text-[#0B1F3A]">
            Accept workspace invite
          </h1>
          <p className="mt-1.5 text-sm text-[#526277]">
            Activate your Proofly staff account and set a password to access your shift clock-in and leave tools.
          </p>
        </div>

        {problem && (
          <div className="mt-5">
            <Alert
              tone="error"
              title={problem.title}
              description={problem.detail}
              meta={`${problem.errorCode} · corrId ${problem.correlationId}`}
            />
          </div>
        )}

        {accepted ? (
          <div className="mt-6 space-y-5">
            <Alert
              tone="success"
              title="Account activated"
              description="Your staff membership is active. Sign in with your work email and new password."
            />
            <Link to="/login" className="block">
              <Button variant="primary" size="lg" className="w-full">
                Sign in to Proofly
              </Button>
            </Link>
          </div>
        ) : (
          <form onSubmit={onSubmit} className="mt-6 space-y-4" noValidate>
            {!hasQueryLink && (
              <>
                <div>
                  <Label htmlFor="invite-tid">Workspace ID (tid)</Label>
                  <Input
                    id="invite-tid"
                    placeholder="Tenant UUID from invite email"
                    className="font-mono text-xs"
                    {...form.register('tenantId')}
                  />
                  {form.formState.errors.tenantId && (
                    <p className="mt-1 text-xs text-red-700">{form.formState.errors.tenantId.message}</p>
                  )}
                </div>
                <div>
                  <Label htmlFor="invite-token">Invite token</Label>
                  <Input
                    id="invite-token"
                    placeholder="Token from invitation email"
                    className="font-mono text-xs"
                    {...form.register('token')}
                  />
                  {form.formState.errors.token && (
                    <p className="mt-1 text-xs text-red-700">{form.formState.errors.token.message}</p>
                  )}
                </div>
              </>
            )}

            <div>
              <Label htmlFor="invite-fullname">Confirm full name (optional)</Label>
              <Input
                id="invite-fullname"
                placeholder="e.g. Chidi Eze"
                {...form.register('fullName')}
              />
            </div>

            <div>
              <Label htmlFor="invite-password">Create password (min 10 characters)</Label>
              <Input
                id="invite-password"
                type="password"
                autoComplete="new-password"
                placeholder="Choose a strong password"
                {...form.register('password')}
              />
              {form.formState.errors.password && (
                <p className="mt-1 text-xs text-red-700">{form.formState.errors.password.message}</p>
              )}
            </div>

            <div>
              <Label htmlFor="invite-confirm-password">Confirm password</Label>
              <Input
                id="invite-confirm-password"
                type="password"
                autoComplete="new-password"
                placeholder="Re-enter password"
                {...form.register('confirmPassword')}
              />
              {form.formState.errors.confirmPassword && (
                <p className="mt-1 text-xs text-red-700">{form.formState.errors.confirmPassword.message}</p>
              )}
            </div>

            <Button
              type="submit"
              variant="primary"
              size="lg"
              className="w-full"
              disabled={form.formState.isSubmitting}
            >
              {form.formState.isSubmitting ? 'Activating account...' : 'Activate staff account'}
            </Button>
          </form>
        )}
      </Card>
    </div>
  );
};
