import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import {
  createProoflyClient,
  normalizeProblemDetail,
  type AuthResponse,
  type AuthUserView,
  type LoginRequest,
  type ProblemDetail,
  type SignupOrgRequest,
} from '@proofly/api-client';

interface AuthContextValue {
  accessToken: string | null;
  user: AuthUserView | null;
  deviceId: string | null;
  isRestoring: boolean;
  apiClient: ReturnType<typeof createProoflyClient>;
  signupOrg: (payload: Omit<SignupOrgRequest, 'clientType' | 'deviceFingerprint' | 'deviceName'>) => Promise<void>;
  login: (payload: Omit<LoginRequest, 'clientType' | 'deviceFingerprint' | 'deviceName'>) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function getDeviceFingerprint(): string {
  const storageKey = 'proofly_device_fp';
  try {
    const existing = window.localStorage.getItem(storageKey);
    if (existing) return existing;
    const created = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? `web-${crypto.randomUUID()}`
      : `web-${Date.now()}`;
    window.localStorage.setItem(storageKey, created);
    return created;
  } catch {
    return 'web-ephemeral';
  }
}

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const [user, setUser] = useState<AuthUserView | null>(null);
  const [deviceId, setDeviceId] = useState<string | null>(null);
  const [isRestoring, setIsRestoring] = useState(true);

  const tokenRef = useRef<string | null>(null);
  tokenRef.current = accessToken;

  const apiBaseUrl = (import.meta.env?.VITE_API_BASE_URL as string | undefined) || '';

  const apiClient = useMemo(
    () =>
      createProoflyClient({
        baseUrl: apiBaseUrl,
        getAccessToken: () => tokenRef.current,
      }),
    [apiBaseUrl]
  );

  const applySession = useCallback((session: AuthResponse) => {
    setAccessToken(session.accessToken);
    setUser(session.user);
    setDeviceId(session.deviceId);
    try {
      window.localStorage.setItem('proofly_has_session', '1');
    } catch {
      // Ignore storage failure
    }
  }, []);

  const clearSession = useCallback(() => {
    setAccessToken(null);
    setUser(null);
    setDeviceId(null);
    try {
      window.localStorage.removeItem('proofly_has_session');
    } catch {
      // Ignore storage failure
    }
  }, []);

  useEffect(() => {
    let active = true;
    const hasHint = (() => {
      try {
        return window.localStorage.getItem('proofly_has_session') === '1';
      } catch {
        return false;
      }
    })();
    const onProtectedPath =
      typeof window !== 'undefined' &&
      (window.location.pathname.startsWith('/workspace') ||
        window.location.pathname.startsWith('/billing/callback'));

    if (!hasHint && !onProtectedPath) {
      setIsRestoring(false);
      return () => {
        active = false;
      };
    }

    (async () => {
      try {
        const { data, error } = await apiClient.POST('/api/v1/auth/refresh', {
          body: {},
        });
        if (active && data && !error) {
          applySession(data);
        } else if (active && error) {
          clearSession();
        }
      } catch {
        // Silent boot check; unauthenticated state is expected when backend offline
      } finally {
        if (active) setIsRestoring(false);
      }
    })();
    return () => {
      active = false;
    };
  }, [apiClient, applySession, clearSession]);

  const signupOrg = useCallback(
    async (payload: Omit<SignupOrgRequest, 'clientType' | 'deviceFingerprint' | 'deviceName'>) => {
      const { data, error } = await apiClient.POST('/api/v1/auth/signup-org', {
        body: {
          ...payload,
          clientType: 'WEB',
          deviceFingerprint: getDeviceFingerprint(),
          deviceName: navigator.userAgent.slice(0, 80),
        },
      });
      if (error || !data) {
        throw normalizeProblemDetail(error, 'Unable to create organization');
      }
      applySession(data);
    },
    [apiClient, applySession]
  );

  const login = useCallback(
    async (payload: Omit<LoginRequest, 'clientType' | 'deviceFingerprint' | 'deviceName'>) => {
      const { data, error } = await apiClient.POST('/api/v1/auth/login', {
        body: {
          ...payload,
          clientType: 'WEB',
          deviceFingerprint: getDeviceFingerprint(),
          deviceName: navigator.userAgent.slice(0, 80),
        },
      });
      if (error || !data) {
        throw normalizeProblemDetail(error, 'Sign in failed');
      }
      applySession(data);
    },
    [apiClient, applySession]
  );

  const logout = useCallback(async () => {
    try {
      await apiClient.POST('/api/v1/auth/logout', { body: {} });
    } finally {
      clearSession();
    }
  }, [apiClient, clearSession]);

  const value: AuthContextValue = {
    accessToken,
    user,
    deviceId,
    isRestoring,
    apiClient,
    signupOrg,
    login,
    logout,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return ctx;
}

export type ApiProblem = ProblemDetail;
