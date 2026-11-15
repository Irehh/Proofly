import React, { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Alert, Button, Card } from '@proofly/ui';
import { normalizeProblemDetail, type ProblemDetail } from '@proofly/api-client';
import { useAuth } from '../lib/authStore';

export const VerifyEmailPage: React.FC = () => {
  const { apiClient, user } = useAuth();
  const [searchParams] = useSearchParams();
  const tid = searchParams.get('tid');
  const token = searchParams.get('token');

  const [status, setStatus] = useState<'idle' | 'verifying' | 'verified' | 'error'>(
    tid && token ? 'verifying' : 'idle'
  );
  const [problem, setProblem] = useState<ProblemDetail | null>(null);

  useEffect(() => {
    if (!tid || !token) return;
    let active = true;
    (async () => {
      setStatus('verifying');
      const { error } = await apiClient.POST('/api/v1/auth/verify-email/confirm', {
        body: { tenantId: tid, token },
      });
      if (!active) return;
      if (error) {
        setProblem(normalizeProblemDetail(error));
        setStatus('error');
      } else {
        setStatus('verified');
      }
    })();
    return () => {
      active = false;
    };
  }, [apiClient, tid, token]);

  return (
    <div className="mx-auto max-w-md px-4 py-12 sm:px-6 lg:py-16">
      <Card className="p-6 sm:p-8">
        <div className="border-b border-slate-200 pb-5">
          <h1 className="font-display text-2xl font-bold tracking-tight text-[#0B1F3A]">
            Email Verification
          </h1>
          <p className="mt-1.5 text-sm text-[#526277]">
            Confirming ownership of your Proofly workspace email address.
          </p>
        </div>

        <div className="mt-6 space-y-5">
          {status === 'idle' && (
            <Alert
              tone="info"
              title="Verification link incomplete"
              description="Open the full verification link from your Proofly welcome email (captured in Mailpit at localhost:8025 during local testing)."
            />
          )}

          {status === 'verifying' && (
            <p className="text-sm text-[#526277]">Verifying single-use token...</p>
          )}

          {status === 'verified' && (
            <Alert
              tone="success"
              title="Email address verified"
              description="Your email address is now confirmed for account recovery and billing notices."
            />
          )}

          {status === 'error' && problem && (
            <Alert
              tone="error"
              title={problem.title}
              description={problem.detail}
              meta={`${problem.errorCode} · corrId ${problem.correlationId}`}
            />
          )}

          <div className="flex flex-wrap gap-3 pt-2">
            <Link to={user ? '/workspace' : '/login'} className="flex-1">
              <Button variant="primary" size="md" className="w-full">
                {user ? 'Go to Workspace' : 'Sign in to Proofly'}
              </Button>
            </Link>
          </div>
        </div>
      </Card>
    </div>
  );
};
