import React, { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
  Card,
  GATEWAY_DISPLAY_NAME,
  Input,
  Label,
  PROOFLY_PLANS,
  formatMoneyMinor,
  getDisplayPlanPrice,
  getPaymentGatewayForCurrency,
  type SupportedCurrency,
} from '@proofly/ui';
import {
  generateCorrelationId,
  normalizeProblemDetail,
  type CheckoutResponse,
  type ProblemDetail,
} from '@proofly/api-client';
import { useAuth } from '../../lib/authStore';
import { checkoutSchema, type CheckoutFormValues } from '../../lib/validation';
import { partitionCurrencyBalances, resolvePreferredSettlementCurrency } from '../../lib/roleHelpers';

interface BillingAndGovernancePanelProps {
  section: 'billing' | 'outbox' | 'audit';
}

export const BillingAndGovernancePanel: React.FC<BillingAndGovernancePanelProps> = ({ section }) => {
  const { user, apiClient } = useAuth();
  const queryClient = useQueryClient();
  const [searchParams] = useSearchParams();

  const [panelProblem, setPanelProblem] = useState<ProblemDetail | null>(null);
  const [panelNotice, setPanelNotice] = useState<string | null>(null);
  const [checkoutResult, setCheckoutResult] = useState<CheckoutResponse | null>(null);
  const [expandedAuditId, setExpandedAuditId] = useState<string | null>(null);

  const plansQuery = useQuery({
    queryKey: ['billing', 'plans'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/billing/plans');
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user && section === 'billing',
  });

  const subscriptionQuery = useQuery({
    queryKey: ['billing', 'subscription'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/billing/subscription');
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user && section === 'billing',
  });

  const paymentsQuery = useQuery({
    queryKey: ['billing', 'payments'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/billing/payments', {
        params: { query: { limit: 25 } },
      });
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user && section === 'billing',
  });

  const outboxQuery = useQuery({
    queryKey: ['notifications', 'outbox'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/notifications/outbox', {
        params: { query: { limit: 25 } },
      });
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user && section === 'outbox',
  });

  const auditQuery = useQuery({
    queryKey: ['audit', 'tenant-logs'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/v1/audit/tenant-logs', {
        params: { query: { limit: 50 } },
      });
      if (error || !data) throw normalizeProblemDetail(error);
      return data;
    },
    enabled: !!user && section === 'audit',
  });

  const retryOutboxMutation = useMutation({
    mutationFn: async (notificationId: string) => {
      const { error } = await apiClient.POST('/api/v1/notifications/outbox/{notificationId}/retry', {
        params: { path: { notificationId } },
      });
      if (error) throw normalizeProblemDetail(error);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notifications', 'outbox'] });
      setPanelNotice('Notification re-queued for immediate ShedLock delivery.');
    },
    onError: (err) => setPanelProblem(normalizeProblemDetail(err)),
  });

  const preselectedPlan = searchParams.get('plan')?.toUpperCase();
  const validPaidPlan =
    preselectedPlan && ['STARTER', 'GROWTH', 'ENTERPRISE'].includes(preselectedPlan)
      ? (preselectedPlan as CheckoutFormValues['planCode'])
      : 'GROWTH';

  const checkoutForm = useForm<CheckoutFormValues>({
    resolver: zodResolver(checkoutSchema),
    defaultValues: {
      planCode: validPaidPlan,
      billingCycle: searchParams.get('cycle')?.toUpperCase() === 'ANNUAL' ? 'ANNUAL' : 'MONTHLY',
      currency: resolvePreferredSettlementCurrency(
        subscriptionQuery.data?.currency,
        searchParams.get('currency')
      ),
      payerEmail: user?.email ?? '',
    },
  });

  // Preserve the exact settled currency (e.g. USD) without resetting to NGN
  useEffect(() => {
    if (subscriptionQuery.data?.currency && !searchParams.get('currency')) {
      const preferred = resolvePreferredSettlementCurrency(subscriptionQuery.data.currency, null);
      checkoutForm.setValue('currency', preferred);
    }
  }, [subscriptionQuery.data?.currency, searchParams, checkoutForm]);

  const watchedPlanCode = checkoutForm.watch('planCode');
  const watchedCycle = checkoutForm.watch('billingCycle');
  const watchedCurrency = checkoutForm.watch('currency');
  const routedGateway = getPaymentGatewayForCurrency(watchedCurrency);
  const routedGatewayName = GATEWAY_DISPLAY_NAME[routedGateway];

  const previewPrice = (() => {
    const serverPlan = (plansQuery.data ?? []).find((p) => p.code === watchedPlanCode);
    const serverMatch = serverPlan?.prices?.find((pr) => pr.currency === watchedCurrency);
    if (serverMatch) {
      const amount = watchedCycle === 'ANNUAL' ? serverMatch.annualPriceMinor : serverMatch.monthlyPriceMinor;
      return formatMoneyMinor(amount, watchedCurrency);
    }
    const fallbackPlan = PROOFLY_PLANS.find((p) => p.code === watchedPlanCode) ?? PROOFLY_PLANS[2];
    return getDisplayPlanPrice(fallbackPlan, watchedCycle, watchedCurrency).formattedTotal;
  })();

  const submitCheckout = checkoutForm.handleSubmit(async (values) => {
    setPanelProblem(null);
    setCheckoutResult(null);
    const idempotencyKey = `chk-${generateCorrelationId()}`;
    const { data, error } = await apiClient.POST('/api/v1/billing/checkout', {
      params: { header: { 'Idempotency-Key': idempotencyKey } },
      body: {
        planCode: values.planCode,
        billingCycle: values.billingCycle,
        currency: values.currency,
        payerEmail: values.payerEmail,
      },
    });
    if (error || !data) {
      setPanelProblem(normalizeProblemDetail(error));
      return;
    }
    setCheckoutResult(data);
  });

  const sortedBalances = partitionCurrencyBalances(subscriptionQuery.data?.currencyBalances ?? []);

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

      {section === 'billing' && (
        <>
          <div className="grid grid-cols-1 gap-6 lg:grid-cols-12">
            <Card className="p-6 lg:col-span-5">
              <h2 className="font-display text-base font-bold text-[#0B1F3A]">
                Subscription &amp; Isolated Currency Ledger
              </h2>
              <p className="mt-1 text-xs text-[#526277]">
                Zero FX conversion: payments and credit balances remain strictly in the exact currency paid (e.g. USD stays USD via Stripe; NGN stays NGN via Paystack).
              </p>

              {subscriptionQuery.isLoading && <p className="mt-3 text-sm text-[#526277]">Loading subscription...</p>}
              {subscriptionQuery.data && (
                <>
                  <dl className="mt-4 divide-y divide-slate-100 text-sm">
                    <div className="flex justify-between py-2.5">
                      <dt className="text-[#526277]">Active plan</dt>
                      <dd className="font-semibold text-[#0B1F3A]">
                        {subscriptionQuery.data.planName} ({subscriptionQuery.data.planCode})
                      </dd>
                    </div>
                    <div className="flex justify-between py-2.5">
                      <dt className="text-[#526277]">Billing cycle</dt>
                      <dd className="font-mono text-xs text-[#0B1F3A]">{subscriptionQuery.data.billingCycle}</dd>
                    </div>
                    <div className="flex justify-between py-2.5">
                      <dt className="text-[#526277]">Settled currency &amp; gateway</dt>
                      <dd className="font-mono text-xs font-semibold text-[#0B1F3A]">
                        {subscriptionQuery.data.currency} · {GATEWAY_DISPLAY_NAME[subscriptionQuery.data.gateway]}
                      </dd>
                    </div>
                    <div className="flex justify-between py-2.5">
                      <dt className="text-[#526277]">Subscription status</dt>
                      <dd className="font-mono text-xs font-semibold text-[#09A55A]">{subscriptionQuery.data.status}</dd>
                    </div>
                    <div className="flex justify-between py-2.5">
                      <dt className="text-[#526277]">Active currency credit</dt>
                      <dd className="font-mono font-semibold tabular-nums text-[#0B1F3A]">
                        {formatMoneyMinor(
                          subscriptionQuery.data.creditBalanceMinor,
                          (subscriptionQuery.data.currency as SupportedCurrency) || 'NGN'
                        )}
                      </dd>
                    </div>
                    {subscriptionQuery.data.currentPeriodEnd && (
                      <div className="flex justify-between py-2.5">
                        <dt className="text-[#526277]">Period ends</dt>
                        <dd className="font-mono text-xs tabular-nums text-[#526277]">
                          {new Date(subscriptionQuery.data.currentPeriodEnd).toLocaleDateString()}
                        </dd>
                      </div>
                    )}
                  </dl>

                  {sortedBalances.length > 0 && (
                    <div className="mt-5 border-t border-slate-200 pt-4">
                      <h3 className="text-xs font-semibold text-[#0B1F3A]">
                        Per-Currency Ledger Accounts (No FX Conversion)
                      </h3>
                      <div className="mt-2 space-y-2">
                        {sortedBalances.map((bal) => (
                          <div
                            key={bal.currency}
                            className="flex items-center justify-between rounded-lg border border-slate-200 bg-[#F7F9FC] px-3 py-2 text-xs"
                          >
                            <span className="font-mono font-semibold text-[#0B1F3A]">
                              {bal.currency} · {GATEWAY_DISPLAY_NAME[bal.gateway]}
                            </span>
                            <span className="font-mono font-semibold tabular-nums text-[#0B1F3A]">
                              {formatMoneyMinor(bal.creditBalanceMinor, bal.currency as SupportedCurrency)}
                            </span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </>
              )}
            </Card>

            <Card className="p-6 lg:col-span-7">
              <h2 className="font-display text-base font-bold text-[#0B1F3A]">Upgrade Subscription Checkout</h2>
              <p className="mt-1 text-xs text-[#526277]">
                Routed automatically by currency: NGN/GHS/KES via Paystack, USD/GBP via Stripe — settled into isolated currency ledger accounts without conversion.
              </p>

              <form onSubmit={submitCheckout} className="mt-5 space-y-4" noValidate>
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-12">
                  <div className="sm:col-span-5">
                    <Label htmlFor="checkout-plan">Target paid plan</Label>
                    <select
                      id="checkout-plan"
                      className="w-full min-h-[44px] rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-[#0B1F3A]"
                      {...checkoutForm.register('planCode')}
                    >
                      {(plansQuery.data ?? [])
                        .filter((p) => p.monthlyPriceMinor > 0)
                        .map((p) => (
                          <option key={p.code} value={p.code}>
                            {p.name} · {p.maxMembers} staff
                          </option>
                        ))}
                      {(!plansQuery.data || plansQuery.data.length === 0) && (
                        <>
                          <option value="STARTER">Starter · 25 staff</option>
                          <option value="GROWTH">Growth · 100 staff</option>
                          <option value="ENTERPRISE">Enterprise · 5,000 staff</option>
                        </>
                      )}
                    </select>
                  </div>

                  <div className="sm:col-span-3">
                    <Label htmlFor="checkout-cycle">Billing interval</Label>
                    <select
                      id="checkout-cycle"
                      className="w-full min-h-[44px] rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-[#0B1F3A]"
                      {...checkoutForm.register('billingCycle')}
                    >
                      <option value="MONTHLY">Monthly</option>
                      <option value="ANNUAL">Annual (-10%)</option>
                    </select>
                  </div>

                  <div className="sm:col-span-4">
                    <Label htmlFor="checkout-currency">Settlement currency</Label>
                    <select
                      id="checkout-currency"
                      className="w-full min-h-[44px] rounded-lg border border-slate-300 bg-white px-3 py-2 font-mono text-sm text-[#0B1F3A]"
                      {...checkoutForm.register('currency')}
                    >
                      <option value="NGN">NGN (Paystack)</option>
                      <option value="USD">USD (Stripe)</option>
                      <option value="GBP">GBP (Stripe)</option>
                      <option value="KES">KES (Paystack)</option>
                      <option value="GHS">GHS (Paystack)</option>
                    </select>
                  </div>
                </div>

                <div>
                  <Label htmlFor="checkout-email">Billing receipt email</Label>
                  <Input
                    id="checkout-email"
                    type="email"
                    placeholder="finance@company.com"
                    {...checkoutForm.register('payerEmail')}
                  />
                  {checkoutForm.formState.errors.payerEmail && (
                    <p className="mt-1 text-xs text-red-700">{checkoutForm.formState.errors.payerEmail.message}</p>
                  )}
                </div>

                <div className="flex flex-wrap items-center justify-between gap-4 rounded-lg border border-slate-200 bg-[#F7F9FC] px-4 py-3 text-xs">
                  <div>
                    <span className="text-[#526277]">Total due via {routedGatewayName}</span>
                    <span className="ml-2 font-display text-base font-bold tabular-nums text-[#0B1F3A]">
                      {previewPrice}
                    </span>
                  </div>
                  <Button
                    type="submit"
                    variant="primary"
                    size="md"
                    disabled={checkoutForm.formState.isSubmitting}
                  >
                    {checkoutForm.formState.isSubmitting
                      ? `Initializing ${routedGatewayName}...`
                      : `Pay via ${routedGatewayName}`}
                  </Button>
                </div>
              </form>

              {checkoutResult && (
                <div className="mt-6 space-y-3 border-t border-slate-200 pt-5">
                  <Alert
                    tone="success"
                    title={`${GATEWAY_DISPLAY_NAME[checkoutResult.gateway]} checkout initialized`}
                    description={`Reference ${checkoutResult.reference} created for ${formatMoneyMinor(
                      checkoutResult.amountMinor,
                      (checkoutResult.currency as SupportedCurrency) || 'NGN'
                    )}.`}
                  />
                  <div className="flex flex-wrap items-center gap-3">
                    <a href={checkoutResult.authorizationUrl} target="_blank" rel="noreferrer">
                      <Button variant="secondary" size="sm">
                        Open {GATEWAY_DISPLAY_NAME[checkoutResult.gateway]} Checkout URL
                      </Button>
                    </a>
                    <span className="font-mono text-xs text-[#526277]">Ref: {checkoutResult.reference}</span>
                  </div>
                </div>
              )}
            </Card>
          </div>

          {/* Payment Receipts Ledger */}
          <Card className="p-6">
            <h2 className="font-display text-base font-bold text-[#0B1F3A]">
              Payment Receipts (Settled In Original Currency)
            </h2>
            {paymentsQuery.isLoading && <p className="mt-3 text-sm text-[#526277]">Loading payment receipts...</p>}
            {!paymentsQuery.isLoading && (paymentsQuery.data ?? []).length === 0 && (
              <p className="mt-3 text-sm text-[#526277]">No gateway payments initiated yet.</p>
            )}
            {(paymentsQuery.data ?? []).length > 0 && (
              <div className="mt-4 overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead>
                    <tr className="border-b border-slate-200 text-xs text-[#526277]">
                      <th className="py-2 pr-4 font-semibold">Reference</th>
                      <th className="py-2 pr-4 font-semibold">Plan</th>
                      <th className="py-2 pr-4 font-semibold">Gateway</th>
                      <th className="py-2 pr-4 font-semibold">Amount Paid</th>
                      <th className="py-2 pr-4 font-semibold">Status</th>
                      <th className="py-2 font-semibold">Date</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {(paymentsQuery.data ?? []).map((p) => (
                      <tr key={p.id}>
                        <td className="py-3 pr-4 font-mono text-xs text-[#0B1F3A]">{p.reference}</td>
                        <td className="py-3 pr-4 text-xs text-[#0B1F3A]">
                          {p.planName} · {p.billingCycle}
                        </td>
                        <td className="py-3 pr-4 font-mono text-xs text-[#526277]">{p.gateway}</td>
                        <td className="py-3 pr-4 font-mono text-xs font-semibold tabular-nums text-[#0B1F3A]">
                          {formatMoneyMinor(p.amountMinor, p.currency as SupportedCurrency)}
                        </td>
                        <td className="py-3 pr-4 font-mono text-xs font-semibold">
                          <span
                            className={
                              p.status === 'SUCCEEDED'
                                ? 'text-[#09A55A]'
                                : p.status === 'INITIATED'
                                  ? 'text-amber-700'
                                  : 'text-red-700'
                            }
                          >
                            {p.status}
                          </span>
                        </td>
                        <td className="py-3 font-mono text-xs tabular-nums text-[#526277]">
                          {new Date(p.settledAt ?? p.initiatedAt).toLocaleString()}
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

      {section === 'outbox' && (
        <Card className="p-6">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div>
              <h2 className="font-display text-base font-bold text-[#0B1F3A]">Tenant Notification Outbox</h2>
              <p className="mt-1 text-xs text-[#526277]">
                Recipients are masked at rest on read views. Failed or dead-letter jobs can be re-queued for immediate ShedLock delivery.
              </p>
            </div>
            <Button
              variant="outline"
              size="sm"
              onClick={() => queryClient.invalidateQueries({ queryKey: ['notifications', 'outbox'] })}
            >
              Refresh outbox
            </Button>
          </div>

          {outboxQuery.isLoading && <p className="mt-4 text-sm text-[#526277]">Loading notification outbox...</p>}
          {!outboxQuery.isLoading && (outboxQuery.data ?? []).length === 0 && (
            <p className="mt-4 text-sm text-[#526277]">No notifications queued for this tenant yet.</p>
          )}
          {(outboxQuery.data ?? []).length > 0 && (
            <div className="mt-4 overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-slate-200 text-xs text-[#526277]">
                    <th className="py-2 pr-4 font-semibold">Channel</th>
                    <th className="py-2 pr-4 font-semibold">Recipient</th>
                    <th className="py-2 pr-4 font-semibold">Template</th>
                    <th className="py-2 pr-4 font-semibold">Status</th>
                    <th className="py-2 pr-4 font-semibold">Attempts</th>
                    <th className="py-2 text-right font-semibold">Action</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {(outboxQuery.data ?? []).map((item) => {
                    const retryable = item.status === 'FAILED' || item.status === 'DEAD_LETTER';
                    return (
                      <tr key={item.id}>
                        <td className="py-3 pr-4 font-mono text-xs text-[#0B1F3A]">{item.channel}</td>
                        <td className="py-3 pr-4 font-mono text-xs text-[#526277]">{item.recipient}</td>
                        <td className="py-3 pr-4 font-mono text-xs text-[#0B1F3A]">{item.templateCode}</td>
                        <td className="py-3 pr-4 font-mono text-xs font-semibold text-[#0B1F3A]">
                          {item.status}
                          {item.lastError && (
                            <span className="block font-sans font-normal text-red-700">{item.lastError}</span>
                          )}
                        </td>
                        <td className="py-3 pr-4 font-mono text-xs tabular-nums text-[#526277]">
                          {item.attemptCount} / {item.maxAttempts}
                        </td>
                        <td className="py-3 text-right">
                          {retryable ? (
                            <Button
                              variant="outline"
                              size="sm"
                              onClick={() => retryOutboxMutation.mutate(item.id)}
                              disabled={retryOutboxMutation.isPending}
                            >
                              Retry
                            </Button>
                          ) : (
                            <span className="text-xs text-slate-400">—</span>
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
      )}

      {section === 'audit' && (
        <Card className="p-6">
          <div className="flex flex-wrap items-center justify-between gap-4 border-b border-slate-200 pb-4">
            <div>
              <h2 className="font-display text-base font-bold text-[#0B1F3A]">
                Immutable Tenant Audit Trail (SHA-256 Hash-Chained)
              </h2>
              <p className="mt-0.5 text-xs text-[#526277]">
                Append-only record of who changed what, when, and why across sites, roles, corrections, and leave decisions.
              </p>
            </div>
            <Button
              variant="outline"
              size="sm"
              onClick={() => queryClient.invalidateQueries({ queryKey: ['audit', 'tenant-logs'] })}
            >
              Refresh log
            </Button>
          </div>

          {auditQuery.isLoading && <p className="mt-4 text-sm text-[#526277]">Loading audit entries...</p>}
          {!auditQuery.isLoading && (auditQuery.data ?? []).length === 0 && (
            <p className="mt-4 text-sm text-[#526277]">No audit log entries recorded yet.</p>
          )}
          {(auditQuery.data ?? []).length > 0 && (
            <div className="mt-4 overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-slate-200 text-xs text-[#526277]">
                    <th className="py-2 pr-4 font-semibold">Timestamp</th>
                    <th className="py-2 pr-4 font-semibold">Actor</th>
                    <th className="py-2 pr-4 font-semibold">Action</th>
                    <th className="py-2 pr-4 font-semibold">Entity</th>
                    <th className="py-2 pr-4 font-semibold">Chain Hash</th>
                    <th className="py-2 text-right font-semibold">Details</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {(auditQuery.data ?? []).map((entry) => {
                    const expanded = expandedAuditId === entry.id;
                    return (
                      <React.Fragment key={entry.id}>
                        <tr>
                          <td className="py-3 pr-4 font-mono text-xs tabular-nums text-[#526277]">
                            {new Date(entry.createdAt).toLocaleString()}
                          </td>
                          <td className="py-3 pr-4 text-xs font-medium text-[#0B1F3A]">{entry.actorName}</td>
                          <td className="py-3 pr-4 font-mono text-xs font-semibold text-[#0B1F3A]">{entry.action}</td>
                          <td className="py-3 pr-4 font-mono text-xs text-[#526277]">
                            {entry.entityType} · {entry.entityId.slice(0, 12)}
                          </td>
                          <td className="py-3 pr-4 font-mono text-xs text-[#526277]">
                            {entry.rowHash.slice(0, 14)}…
                          </td>
                          <td className="py-3 text-right">
                            <Button
                              variant="outline"
                              size="sm"
                              onClick={() => setExpandedAuditId(expanded ? null : entry.id)}
                            >
                              {expanded ? 'Hide' : 'Inspect'}
                            </Button>
                          </td>
                        </tr>
                        {expanded && (
                          <tr>
                            <td colSpan={6} className="bg-[#F7F9FC] p-3 font-mono text-xs text-[#0B1F3A]">
                              <p>reqId: {entry.requestId ?? 'system'}</p>
                              {entry.beforeStateJson && <p className="mt-1">before: {entry.beforeStateJson}</p>}
                              {entry.afterStateJson && <p className="mt-1">after: {entry.afterStateJson}</p>}
                            </td>
                          </tr>
                        )}
                      </React.Fragment>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      )}
    </div>
  );
};
