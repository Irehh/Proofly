import createClient from 'openapi-fetch';
import type { components, paths } from './schema';

export type { paths, components };
export type AuthResponse = components['schemas']['AuthResponse'];
export type AuthUserView = components['schemas']['AuthUserView'];
export type SignupOrgRequest = components['schemas']['SignupOrgRequest'];
export type LoginRequest = components['schemas']['LoginRequest'];
export type DeviceView = components['schemas']['DeviceView'];
export type TenantSummary = components['schemas']['TenantSummary'];
export type ProblemDetail = components['schemas']['ProblemDetail'];
export type MfaSetupResponse = components['schemas']['MfaSetupResponse'];
export type PasswordResetRequest = components['schemas']['PasswordResetRequest'];
export type PasswordResetConfirmRequest = components['schemas']['PasswordResetConfirmRequest'];
export type EmailVerifyConfirmRequest = components['schemas']['EmailVerifyConfirmRequest'];
export type PlanPriceView = components['schemas']['PlanPriceView'];
export type PlanView = components['schemas']['PlanView'];
export type SubscriptionView = components['schemas']['SubscriptionView'];
export type CheckoutRequest = components['schemas']['CheckoutRequest'];
export type CheckoutResponse = components['schemas']['CheckoutResponse'];
export type VerifyPaymentResponse = components['schemas']['VerifyPaymentResponse'];
export type OutboxView = components['schemas']['OutboxView'];
export type GatewayCode = components['schemas']['GatewayCode'];
export type Role = components['schemas']['Role'];
export type CurrencyBalanceView = components['schemas']['CurrencyBalanceView'];
export type PaymentReceiptView = components['schemas']['PaymentReceiptView'];
export type OrgSettingsView = components['schemas']['OrgSettingsView'];
export type MemberSummary = components['schemas']['MemberSummary'];
export type InvitationView = components['schemas']['InvitationView'];
export type SiteView = components['schemas']['SiteView'];
export type SiteAssignmentView = components['schemas']['SiteAssignmentView'];
export type SiteAssignmentItem = components['schemas']['SiteAssignmentItem'];
export type AttendanceEventView = components['schemas']['AttendanceEventView'];
export type CorrectionView = components['schemas']['CorrectionView'];
export type LeaveRequestView = components['schemas']['LeaveRequestView'];
export type LiveBoardResponse = components['schemas']['LiveBoardResponse'];
export type PayrollRow = components['schemas']['PayrollRow'];
export type TenantAuditEntryView = components['schemas']['TenantAuditEntryView'];

export interface ProoflyApiOptions {
  baseUrl: string;
  getAccessToken?: () => string | null;
  correlationIdFactory?: () => string;
}

export function generateCorrelationId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `req-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
}

export function normalizeProblemDetail(err: unknown, fallbackMessage = 'Request failed'): ProblemDetail {
  if (err && typeof err === 'object' && 'errorCode' in err && 'detail' in err) {
    return err as ProblemDetail;
  }
  const detail = err instanceof Error ? err.message : fallbackMessage;
  return {
    type: 'https://api.proofly.com/errors/network_error',
    title: 'API Connection Error',
    status: 503,
    detail,
    errorCode: 'PROOFLY_NETWORK_UNAVAILABLE',
    correlationId: generateCorrelationId(),
    timestamp: new Date().toISOString(),
  };
}

export function createProoflyClient(options: ProoflyApiOptions) {
  const client = createClient<paths>({
    baseUrl: options.baseUrl,
    credentials: 'include',
  });

  client.use({
    onRequest({ request }) {
      const headers = new Headers(request.headers);
      if (!headers.has('X-Request-Id')) {
        const cid = options.correlationIdFactory ? options.correlationIdFactory() : generateCorrelationId();
        headers.set('X-Request-Id', cid);
      }
      const token = options.getAccessToken?.();
      if (token && !headers.has('Authorization')) {
        headers.set('Authorization', `Bearer ${token}`);
      }
      return new Request(request, { headers });
    },
  });

  return client;
}
