import React, { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Button, Card, Input, Label } from '@proofly/ui';
import { normalizeProblemDetail, type ProblemDetail } from '@proofly/api-client';
import { useAuth } from '../lib/authStore';
import { resetPasswordSchema, type ResetPasswordFormValues } from '../lib/validation';

export const ResetPasswordPage: React.FC = () => {
  const { apiClient } = useAuth();
  const [searchParams] = useSearchParams();
  const [problem, setProblem] = useState<ProblemDetail | null>(null);
  const [completed, setCompleted] = useState(false);

  const form = useForm<ResetPasswordFormValues>({
    resolver: zodResolver(resetPasswordSchema),
    defaultValues: {
      tenantId: searchParams.get('tid') ?? '',
      token: searchParams.get('token') ?? '',
      newPassword: '',
      confirmPassword: '',
    },
  });

  const onSubmit = form.handleSubmit(async (values) => {
    setProblem(null);
    const { error } = await apiClient.POST('/api/v1/auth/password-reset/confirm', {
      body: {
        tenantId: values.tenantId,
        token: values.token,
        newPassword: values.newPassword,
      },
    });
    if (error) {
      setProblem(normalizeProblemDetail(error));
      return;
    }
    setCompleted(true);
  });

  const hasQueryLink = Boolean(searchParams.get('tid') && searchParams.get('token'));

  return (
    <div className="mx-auto max-w-md px-4 py-12 sm:px-6 lg:py-16">
      <Card className="p-6 sm:p-8">
        <div className="border-b border-slate-200 pb-5">
          <h1 className="font-display text-2xl font-bold tracking-tight text-[#0B1F3A]">
            Choose a new password
          </h1>
          <p className="mt-1.5 text-sm text-[#526277]">
            Confirming a password reset revokes all active refresh sessions across every registered device.
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

        {completed ? (
          <div className="mt-6 space-y-5">
            <Alert
              tone="success"
              title="Password updated"
              description="Your password has been changed and all prior sessions were revoked. Sign in with your new credentials."
            />
            <Link to="/login" className="block">
              <Button variant="primary" size="lg" className="w-full">
                Sign in now
              </Button>
            </Link>
          </div>
        ) : (
          <form onSubmit={onSubmit} className="mt-6 space-y-4" noValidate>
            {!hasQueryLink && (
              <>
                <div>
                  <Label htmlFor="reset-tid">Workspace ID (tid)</Label>
                  <Input
                    id="reset-tid"
                    placeholder="Tenant UUID from reset link"
                    className="font-mono text-xs"
                    {...form.register('tenantId')}
                  />
                  {form.formState.errors.tenantId && (
                    <p className="mt-1 text-xs text-red-700">{form.formState.errors.tenantId.message}</p>
                  )}
                </div>
                <div>
                  <Label htmlFor="reset-token">Reset token</Label>
                  <Input
                    id="reset-token"
                    placeholder="Single-use token from email"
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
              <Label htmlFor="reset-new-password">New password (min 10 characters)</Label>
              <Input
                id="reset-new-password"
                type="password"
                autoComplete="new-password"
                placeholder="Enter strong password"
                {...form.register('newPassword')}
              />
              {form.formState.errors.newPassword && (
                <p className="mt-1 text-xs text-red-700">{form.formState.errors.newPassword.message}</p>
              )}
            </div>

            <div>
              <Label htmlFor="reset-confirm-password">Confirm new password</Label>
              <Input
                id="reset-confirm-password"
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
              {form.formState.isSubmitting ? 'Updating password...' : 'Update password'}
            </Button>

            <div className="border-t border-slate-200 pt-4 text-center text-sm text-[#526277]">
              Need a new link?{' '}
              <Link to="/forgot-password" className="font-semibold text-[#0B1F3A] underline underline-offset-4 hover:text-[#09A55A]">
                Request another reset
              </Link>
            </div>
          </form>
        )}
      </Card>
    </div>
  );
};
