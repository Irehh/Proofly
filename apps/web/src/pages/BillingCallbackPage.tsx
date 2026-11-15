import React, { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Alert, Button, Card, GATEWAY_DISPLAY_NAME, formatMoneyMinor, type SupportedCurrency } from '@proofly/ui';
import { normalizeProblemDetail, type ProblemDetail, type VerifyPaymentResponse } from '@proofly/api-client';
import { useAuth } from '../lib/authStore';

export const BillingCallbackPage: React.FC = () => {
  const { apiClient, user, isRestoring } = useAuth();
  const [searchParams] = useSearchParams();
  const reference = searchParams.get('reference') ?? searchParams.get('trxref') ?? '';

  const [result, setResult] = useState<VerifyPaymentResponse | null>(null);
  const [problem, setProblem] = useState<ProblemDetail | null>(null);
  const [loading, setLoading] = useState(false);

  const runVerify = async () => {
    if (!reference) return;
    setLoading(true);
    setProblem(null);
    const { data, error } = await apiClient.GET('/api/v1/billing/verify', {
      params: { query: { reference } },
    });
    setLoading(false);
    if (error || !data) {
      setProblem(normalizeProblemDetail(error));
      return;
    }
    setResult(data);
  };

  useEffect(() => {
    if (!isRestoring && user && reference) {
      void runVerify();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isRestoring, user, reference]);

  return (
    <div className="mx-auto max-w-lg px-4 py-12 sm:px-6 lg:py-16">
      <Card className="p-6 sm:p-8">
        <div className="border-b border-slate-200 pb-5">
          <h1 className="font-display text-2xl font-bold tracking-tight text-[#0B1F3A]">
            Payment Settlement Verification
          </h1>
          <p className="mt-1.5 text-sm text-[#526277]">
            Confirming gateway verification, webhook settlement, and double-entry ledger activation for your workspace.
          </p>
        </div>

        <div className="mt-6 space-y-5">
          {!reference && (
            <Alert
              tone="info"
              title="Missing transaction reference"
              description="Open this callback page from gateway checkout with a ?reference= query parameter."
            />
          )}

          {reference && !isRestoring && !user && (
            <Alert
              tone="info"
              title="Sign in required to verify payment"
              description={`Transaction reference ${reference} detected. Sign in as your organization administrator to view ledger settlement status.`}
            />
          )}

          {loading && <p className="text-sm text-[#526277]">Checking settlement status with payment gateway...</p>}

          {problem && (
            <Alert
              tone="error"
              title={problem.title}
              description={problem.detail}
              meta={`${problem.errorCode} · corrId ${problem.correlationId}`}
            />
          )}

          {result && (
            <div className="space-y-4">
              <Alert
                tone={result.localStatus === 'SUCCEEDED' ? 'success' : 'info'}
                title={
                  result.localStatus === 'SUCCEEDED'
                    ? 'Subscription settled in ledger'
                    : `Payment status: ${result.localStatus}`
                }
                description={
                  result.localStatus === 'SUCCEEDED'
                    ? `Verified via ${GATEWAY_DISPLAY_NAME[result.gateway]}. Double-entry settlement and revenue entries are posted and your plan is active.`
                    : `Gateway reports "${result.providerStatus}". If webhook delivery is still in flight, refresh verification in a moment.`
                }
              />

              <dl className="divide-y divide-slate-100 border-t border-slate-200 text-sm">
                <div className="flex justify-between py-2.5">
                  <dt className="text-[#526277]">Gateway</dt>
                  <dd className="font-mono text-xs font-semibold text-[#0B1F3A]">
                    {GATEWAY_DISPLAY_NAME[result.gateway]} ({result.gateway})
                  </dd>
                </div>
                <div className="flex justify-between py-2.5">
                  <dt className="text-[#526277]">Reference</dt>
                  <dd className="font-mono text-xs text-[#0B1F3A]">{result.reference}</dd>
                </div>
                <div className="flex justify-between py-2.5">
                  <dt className="text-[#526277]">Amount</dt>
                  <dd className="font-mono font-semibold tabular-nums text-[#0B1F3A]">
                    {formatMoneyMinor(result.amountMinor, (result.currency as SupportedCurrency) || 'NGN')}
                  </dd>
                </div>
                <div className="flex justify-between py-2.5">
                  <dt className="text-[#526277]">Local ledger status</dt>
                  <dd className="font-mono text-xs font-semibold text-[#0B1F3A]">{result.localStatus}</dd>
                </div>
                <div className="flex justify-between py-2.5">
                  <dt className="text-[#526277]">Gateway status</dt>
                  <dd className="font-mono text-xs text-[#526277]">{result.providerStatus}</dd>
                </div>
              </dl>
            </div>
          )}

          <div className="flex flex-wrap gap-3 pt-2">
            {user && reference && (
              <Button variant="outline" size="md" onClick={runVerify} disabled={loading}>
                Refresh status
              </Button>
            )}
            <Link to={user ? '/workspace?tab=billing' : '/login'} className="flex-1">
              <Button variant="primary" size="md" className="w-full">
                {user ? 'Back to Billing' : 'Sign in'}
              </Button>
            </Link>
          </div>
        </div>
      </Card>
    </div>
  );
};
