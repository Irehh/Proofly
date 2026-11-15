import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Button, Card, Input, Label } from '@proofly/ui';
import { normalizeProblemDetail, type ProblemDetail } from '@proofly/api-client';
import { useAuth } from '../lib/authStore';
import { forgotPasswordSchema, type ForgotPasswordFormValues } from '../lib/validation';

export const ForgotPasswordPage: React.FC = () => {
  const { apiClient } = useAuth();
  const [problem, setProblem] = useState<ProblemDetail | null>(null);
  const [submittedEmail, setSubmittedEmail] = useState<string | null>(null);

  const form = useForm<ForgotPasswordFormValues>({
    resolver: zodResolver(forgotPasswordSchema),
    defaultValues: { email: '' },
  });

  const onSubmit = form.handleSubmit(async (values) => {
    setProblem(null);
    const { error } = await apiClient.POST('/api/v1/auth/password-reset/request', {
      body: { email: values.email },
    });
    if (error) {
      setProblem(normalizeProblemDetail(error));
      return;
    }
    setSubmittedEmail(values.email);
  });

  return (
    <div className="mx-auto max-w-md px-4 py-12 sm:px-6 lg:py-16">
      <Card className="p-6 sm:p-8">
        <div className="border-b border-slate-200 pb-5">
          <h1 className="font-display text-2xl font-bold tracking-tight text-[#0B1F3A]">
            Reset your password
          </h1>
          <p className="mt-1.5 text-sm text-[#526277]">
            Enter your work email. If an active workspace matches, we will queue a 15-minute single-use reset link.
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

        {submittedEmail ? (
          <div className="mt-6 space-y-5">
            <Alert
              tone="success"
              title="Reset instructions queued"
              description={`If an active Proofly account exists for ${submittedEmail}, a single-use reset link has been queued in the notification outbox.`}
            />
            <div className="flex flex-wrap items-center justify-between gap-3 pt-2 text-sm">
              <Link to="/login" className="font-semibold text-[#0B1F3A] underline underline-offset-4 hover:text-[#09A55A]">
                Return to sign in
              </Link>
              <button
                type="button"
                onClick={() => setSubmittedEmail(null)}
                className="text-xs font-medium text-[#526277] hover:text-[#0B1F3A] cursor-pointer"
              >
                Try another email
              </button>
            </div>
          </div>
        ) : (
          <form onSubmit={onSubmit} className="mt-6 space-y-5" noValidate>
            <div>
              <Label htmlFor="forgot-email">Work email</Label>
              <Input
                id="forgot-email"
                type="email"
                autoComplete="email"
                placeholder="adaeze@company.com"
                {...form.register('email')}
              />
              {form.formState.errors.email && (
                <p className="mt-1 text-xs text-red-700">{form.formState.errors.email.message}</p>
              )}
            </div>

            <Button
              type="submit"
              variant="primary"
              size="lg"
              className="w-full"
              disabled={form.formState.isSubmitting}
            >
              {form.formState.isSubmitting ? 'Sending reset link...' : 'Send reset link'}
            </Button>

            <div className="border-t border-slate-200 pt-4 text-center text-sm text-[#526277]">
              Remembered your password?{' '}
              <Link to="/login" className="font-semibold text-[#0B1F3A] underline underline-offset-4 hover:text-[#09A55A]">
                Sign in
              </Link>
            </div>
          </form>
        )}
      </Card>
    </div>
  );
};
