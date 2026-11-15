/**
 * Generated from /docs/openapi.yaml (OpenAPI 3.1.0).
 * Never hand-edit business types here; regenerate when /docs/openapi.yaml changes.
 */

export interface paths {
  '/api/v1/auth/signup-org': {
    post: {
      requestBody: {
        content: {
          'application/json': components['schemas']['SignupOrgRequest'];
        };
      };
      responses: {
        201: {
          content: {
            'application/json': components['schemas']['AuthResponse'];
          };
        };
        409: {
          content: {
            'application/problem+json': components['schemas']['ProblemDetail'];
          };
        };
        422: {
          content: {
            'application/problem+json': components['schemas']['ProblemDetail'];
          };
        };
      };
    };
  };
  '/api/v1/auth/login': {
    post: {
      requestBody: {
        content: {
          'application/json': components['schemas']['LoginRequest'];
        };
      };
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['AuthResponse'];
          };
        };
        401: {
          content: {
            'application/problem+json': components['schemas']['ProblemDetail'];
          };
        };
        423: {
          content: {
            'application/problem+json': components['schemas']['ProblemDetail'];
          };
        };
        429: {
          content: {
            'application/problem+json': components['schemas']['ProblemDetail'];
          };
        };
      };
    };
  };
  '/api/v1/auth/refresh': {
    post: {
      requestBody?: {
        content: {
          'application/json': components['schemas']['RefreshRequest'];
        };
      };
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['AuthResponse'];
          };
        };
        401: {
          content: {
            'application/problem+json': components['schemas']['ProblemDetail'];
          };
        };
      };
    };
  };
  '/api/v1/auth/logout': {
    post: {
      requestBody?: {
        content: {
          'application/json': components['schemas']['LogoutRequest'];
        };
      };
      responses: {
        204: {
          content?: never;
        };
      };
    };
  };
  '/api/v1/devices': {
    get: {
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['DeviceView'][];
          };
        };
        401: {
          content: {
            'application/problem+json': components['schemas']['ProblemDetail'];
          };
        };
      };
    };
  };
  '/api/v1/devices/{deviceId}': {
    delete: {
      parameters: {
        path: {
          deviceId: string;
        };
      };
      responses: {
        204: {
          content?: never;
        };
        404: {
          content: {
            'application/problem+json': components['schemas']['ProblemDetail'];
          };
        };
      };
    };
  };
  '/api/v1/auth/mfa/setup': {
    post: {
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['MfaSetupResponse'];
          };
        };
      };
    };
  };
  '/api/v1/auth/mfa/enable': {
    post: {
      requestBody: {
        content: {
          'application/json': components['schemas']['MfaEnableRequest'];
        };
      };
      responses: {
        204: {
          content?: never;
        };
      };
    };
  };
  '/api/v1/tenants/me': {
    get: {
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['TenantSummary'];
          };
        };
        401: {
          content: {
            'application/problem+json': components['schemas']['ProblemDetail'];
          };
        };
      };
    };
  };
}

export interface components {
  schemas: {
    ClientType: 'WEB' | 'MOBILE' | 'API';
    LoginMethod: 'EMAIL_PASSWORD' | 'PHONE_PIN';
    Role: 'SUPER_ADMIN' | 'ADMIN' | 'MANAGER' | 'EMPLOYEE';
    SignupOrgRequest: {
      orgName: string;
      slug: string;
      defaultTimezone?: string;
      fullName: string;
      email: string;
      phoneE164?: string;
      password: string;
      deviceFingerprint?: string;
      deviceName?: string;
      clientType?: components['schemas']['ClientType'];
    };
    LoginRequest: {
      method?: components['schemas']['LoginMethod'];
      email?: string;
      phoneE164?: string;
      password?: string;
      pin?: string;
      totpCode?: string;
      deviceFingerprint?: string;
      deviceName?: string;
      clientType?: components['schemas']['ClientType'];
    };
    RefreshRequest: {
      refreshToken?: string;
    };
    LogoutRequest: {
      refreshToken?: string;
    };
    MfaSetupResponse: {
      base32Secret: string;
      otpauthUri: string;
    };
    MfaEnableRequest: {
      totpCode: string;
    };
    AuthUserView: {
      userId: string;
      tenantId: string;
      membershipId: string;
      fullName: string;
      email?: string;
      phoneE164?: string;
      role: components['schemas']['Role'];
      mfaEnabled: boolean;
    };
    AuthResponse: {
      accessToken: string;
      expiresInSeconds: number;
      tokenType: string;
      refreshToken?: string | null;
      refreshExpiresAt: string;
      deviceId: string;
      user: components['schemas']['AuthUserView'];
    };
    DeviceView: {
      id: string;
      deviceName: string;
      platform: 'WEB_PWA' | 'IOS' | 'ANDROID' | 'API';
      lastSeenAt: string;
      revokedAt?: string | null;
      createdAt: string;
    };
    TenantSummary: {
      id: string;
      slug: string;
      name: string;
      status: 'PENDING' | 'PROVISIONING' | 'ACTIVE' | 'SUSPENDED' | 'DELETING';
      schemaVersion: string;
      currentPlanId: string;
      defaultCurrency: string;
      createdAt: string;
    };
    ProblemDetail: {
      type: string;
      title: string;
      status: number;
      detail: string;
      errorCode: string;
      correlationId: string;
      timestamp: string;
      fields?: Record<string, string>;
    };
  };
}
