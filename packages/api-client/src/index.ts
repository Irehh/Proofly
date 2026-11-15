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
