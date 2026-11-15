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
  '/api/v1/auth/password-reset/request': {
    post: {
      requestBody: {
        content: {
          'application/json': components['schemas']['PasswordResetRequest'];
        };
      };
      responses: {
        202: {
          content?: never;
        };
        429: {
          content: {
            'application/problem+json': components['schemas']['ProblemDetail'];
          };
        };
      };
    };
  };
  '/api/v1/auth/password-reset/confirm': {
    post: {
      requestBody: {
        content: {
          'application/json': components['schemas']['PasswordResetConfirmRequest'];
        };
      };
      responses: {
        204: {
          content?: never;
        };
        400: {
          content: {
            'application/problem+json': components['schemas']['ProblemDetail'];
          };
        };
      };
    };
  };
  '/api/v1/auth/verify-email/confirm': {
    post: {
      requestBody: {
        content: {
          'application/json': components['schemas']['EmailVerifyConfirmRequest'];
        };
      };
      responses: {
        204: {
          content?: never;
        };
        400: {
          content: {
            'application/problem+json': components['schemas']['ProblemDetail'];
          };
        };
      };
    };
  };
  '/api/v1/auth/verify-email/resend': {
    post: {
      responses: {
        202: {
          content?: never;
        };
        401: {
          content: {
            'application/problem+json': components['schemas']['ProblemDetail'];
          };
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
  '/api/v1/billing/plans': {
    get: {
      parameters?: {
        query?: {
          currency?: string;
        };
      };
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['PlanView'][];
          };
        };
      };
    };
  };
  '/api/v1/billing/subscription': {
    get: {
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['SubscriptionView'];
          };
        };
        403: {
          content: {
            'application/problem+json': components['schemas']['ProblemDetail'];
          };
        };
      };
    };
  };
  '/api/v1/billing/checkout': {
    post: {
      parameters: {
        header: {
          'Idempotency-Key': string;
        };
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['CheckoutRequest'];
        };
      };
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['CheckoutResponse'];
          };
        };
        400: {
          content: {
            'application/problem+json': components['schemas']['ProblemDetail'];
          };
        };
      };
    };
  };
  '/api/v1/billing/verify': {
    get: {
      parameters: {
        query: {
          reference: string;
        };
      };
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['VerifyPaymentResponse'];
          };
        };
        404: {
          content: {
            'application/problem+json': components['schemas']['ProblemDetail'];
          };
        };
      };
    };
  };
  '/api/v1/billing/payments': {
    get: {
      parameters?: {
        query?: {
          limit?: number;
        };
      };
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['PaymentReceiptView'][];
          };
        };
      };
    };
  };
  '/api/v1/notifications/outbox': {
    get: {
      parameters?: {
        query?: {
          limit?: number;
        };
      };
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['OutboxView'][];
          };
        };
      };
    };
  };
  '/api/v1/notifications/outbox/{notificationId}/retry': {
    post: {
      parameters: {
        path: {
          notificationId: string;
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
  '/api/v1/org/settings': {
    get: {
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['OrgSettingsView'];
          };
        };
      };
    };
    put: {
      requestBody: {
        content: {
          'application/json': components['schemas']['UpdateOrgSettingsRequest'];
        };
      };
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['OrgSettingsView'];
          };
        };
      };
    };
  };
  '/api/v1/org/members': {
    get: {
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['MemberSummary'][];
          };
        };
      };
    };
  };
  '/api/v1/org/invites': {
    get: {
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['InvitationView'][];
          };
        };
      };
    };
    post: {
      requestBody: {
        content: {
          'application/json': components['schemas']['InviteStaffRequest'];
        };
      };
      responses: {
        201: {
          content: {
            'application/json': components['schemas']['MemberSummary'];
          };
        };
      };
    };
  };
  '/api/v1/org/invites/accept': {
    post: {
      requestBody: {
        content: {
          'application/json': components['schemas']['AcceptInviteRequest'];
        };
      };
      responses: {
        204: {
          content?: never;
        };
      };
    };
  };
  '/api/v1/org/members/{membershipId}': {
    patch: {
      parameters: {
        path: {
          membershipId: string;
        };
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['UpdateMemberRequest'];
        };
      };
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['MemberSummary'];
          };
        };
      };
    };
  };
  '/api/v1/sites': {
    get: {
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['SiteView'][];
          };
        };
      };
    };
    post: {
      requestBody: {
        content: {
          'application/json': components['schemas']['CreateSiteRequest'];
        };
      };
      responses: {
        201: {
          content: {
            'application/json': components['schemas']['SiteView'];
          };
        };
      };
    };
  };
  '/api/v1/sites/{siteId}': {
    patch: {
      parameters: {
        path: {
          siteId: string;
        };
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['UpdateSiteRequest'];
        };
      };
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['SiteView'];
          };
        };
      };
    };
  };
  '/api/v1/sites/assignments/me': {
    get: {
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['SiteAssignmentView'][];
          };
        };
      };
    };
  };
  '/api/v1/sites/assignments/{membershipId}': {
    put: {
      parameters: {
        path: {
          membershipId: string;
        };
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['SetAssignmentsRequest'];
        };
      };
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['SiteAssignmentView'][];
          };
        };
      };
    };
  };
  '/api/v1/attendance/clock-in': {
    post: {
      requestBody: {
        content: {
          'application/json': components['schemas']['ClockRequest'];
        };
      };
      responses: {
        201: {
          content: {
            'application/json': components['schemas']['AttendanceEventView'];
          };
        };
      };
    };
  };
  '/api/v1/attendance/clock-out': {
    post: {
      requestBody: {
        content: {
          'application/json': components['schemas']['ClockRequest'];
        };
      };
      responses: {
        201: {
          content: {
            'application/json': components['schemas']['AttendanceEventView'];
          };
        };
      };
    };
  };
  '/api/v1/attendance/me': {
    get: {
      parameters?: {
        query?: {
          limit?: number;
        };
      };
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['AttendanceEventView'][];
          };
        };
      };
    };
  };
  '/api/v1/attendance/flagged': {
    get: {
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['AttendanceEventView'][];
          };
        };
      };
    };
  };
  '/api/v1/attendance/{eventId}/review': {
    post: {
      parameters: {
        path: {
          eventId: string;
        };
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['ReviewDecisionRequest'];
        };
      };
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['AttendanceEventView'];
          };
        };
      };
    };
  };
  '/api/v1/attendance/corrections': {
    get: {
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['CorrectionView'][];
          };
        };
      };
    };
    post: {
      requestBody: {
        content: {
          'application/json': components['schemas']['CreateCorrectionRequest'];
        };
      };
      responses: {
        201: {
          content: {
            'application/json': components['schemas']['CorrectionView'];
          };
        };
      };
    };
  };
  '/api/v1/attendance/corrections/{correctionId}/review': {
    post: {
      parameters: {
        path: {
          correctionId: string;
        };
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['ReviewDecisionRequest'];
        };
      };
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['CorrectionView'];
          };
        };
      };
    };
  };
  '/api/v1/leave/requests': {
    get: {
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['LeaveRequestView'][];
          };
        };
      };
    };
    post: {
      requestBody: {
        content: {
          'application/json': components['schemas']['CreateLeaveRequest'];
        };
      };
      responses: {
        201: {
          content: {
            'application/json': components['schemas']['LeaveRequestView'];
          };
        };
      };
    };
  };
  '/api/v1/leave/requests/{leaveId}/review': {
    post: {
      parameters: {
        path: {
          leaveId: string;
        };
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['LeaveDecisionRequest'];
        };
      };
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['LeaveRequestView'];
          };
        };
      };
    };
  };
  '/api/v1/leave/requests/{leaveId}/cancel': {
    post: {
      parameters: {
        path: {
          leaveId: string;
        };
      };
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['LeaveRequestView'];
          };
        };
      };
    };
  };
  '/api/v1/reports/live-board': {
    get: {
      parameters?: {
        query?: {
          siteId?: string;
        };
      };
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['LiveBoardResponse'];
          };
        };
      };
    };
  };
  '/api/v1/reports/payroll-summary': {
    get: {
      parameters: {
        query: {
          fromDate: string;
          toDate: string;
          siteId?: string;
        };
      };
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['PayrollRow'][];
          };
        };
      };
    };
  };
  '/api/v1/reports/payroll-csv': {
    get: {
      parameters: {
        query: {
          fromDate: string;
          toDate: string;
          siteId?: string;
        };
      };
      responses: {
        200: {
          content: {
            'text/csv': string;
          };
        };
      };
    };
  };
  '/api/v1/audit/tenant-logs': {
    get: {
      parameters?: {
        query?: {
          limit?: number;
        };
      };
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['TenantAuditEntryView'][];
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
    GatewayCode: 'PAYSTACK' | 'STRIPE';
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
    PasswordResetRequest: {
      email: string;
    };
    PasswordResetConfirmRequest: {
      tenantId: string;
      token: string;
      newPassword: string;
    };
    EmailVerifyConfirmRequest: {
      tenantId: string;
      token: string;
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
    PlanPriceView: {
      currency: string;
      gateway: components['schemas']['GatewayCode'];
      monthlyPriceMinor: number;
      annualPriceMinor: number;
    };
    PlanView: {
      id: string;
      code: string;
      name: string;
      maxMembers: number;
      maxSites: number;
      monthlyPriceMinor: number;
      annualPriceMinor: number;
      currency: string;
      gateway: components['schemas']['GatewayCode'];
      isActive: boolean;
      prices: components['schemas']['PlanPriceView'][];
    };
    CurrencyBalanceView: {
      currency: string;
      gateway: components['schemas']['GatewayCode'];
      creditBalanceMinor: number;
    };
    PaymentReceiptView: {
      id: string;
      reference: string;
      planCode: string;
      planName: string;
      billingCycle: string;
      gateway: components['schemas']['GatewayCode'];
      amountMinor: number;
      currency: string;
      status: string;
      initiatedAt: string;
      settledAt?: string | null;
    };
    SubscriptionView: {
      tenantId: string;
      planCode: string;
      planName: string;
      billingCycle: 'MONTHLY' | 'ANNUAL';
      status: string;
      currentPeriodEnd?: string | null;
      graceUntil?: string | null;
      creditBalanceMinor: number;
      currency: string;
      gateway: components['schemas']['GatewayCode'];
      currencyBalances: components['schemas']['CurrencyBalanceView'][];
    };
    CheckoutRequest: {
      planCode: string;
      billingCycle: 'MONTHLY' | 'ANNUAL';
      payerEmail: string;
      currency?: string;
    };
    CheckoutResponse: {
      paymentId: string;
      gateway: components['schemas']['GatewayCode'];
      reference: string;
      authorizationUrl: string;
      amountMinor: number;
      currency: string;
    };
    VerifyPaymentResponse: {
      gateway: components['schemas']['GatewayCode'];
      reference: string;
      localStatus: string;
      providerStatus: string;
      amountMinor: number;
      currency: string;
    };
    OutboxView: {
      id: string;
      channel: 'EMAIL' | 'SMS';
      recipient: string;
      templateCode: string;
      subject?: string | null;
      status: 'PENDING' | 'PROCESSING' | 'SENT' | 'FAILED' | 'DEAD_LETTER';
      attemptCount: number;
      maxAttempts: number;
      nextAttemptAt: string;
      lastError?: string | null;
      sentAt?: string | null;
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
    OrgSettingsView: {
      orgName: string;
      defaultTimezone: string;
      lateGraceMinutes: number;
      overtimeThresholdMinutes: number;
      maxGpsAccuracyMeters: number;
      locationRetentionDays: number;
    };
    UpdateOrgSettingsRequest: {
      orgName?: string;
      defaultTimezone?: string;
      lateGraceMinutes?: number;
      overtimeThresholdMinutes?: number;
      maxGpsAccuracyMeters?: number;
    };
    MemberSummary: {
      membershipId: string;
      userId: string;
      fullName: string;
      email?: string | null;
      phoneE164?: string | null;
      role: components['schemas']['Role'];
      status: string;
      managerMembershipId?: string | null;
      managerName?: string | null;
      jobTitle?: string | null;
      employeeCode?: string | null;
      assignedSiteIds: string[];
    };
    InviteStaffRequest: {
      inviteType: 'EMAIL' | 'PHONE_PIN';
      fullName: string;
      email?: string;
      phoneE164?: string;
      initialPin?: string;
      role: 'ADMIN' | 'MANAGER' | 'EMPLOYEE';
      managerMembershipId?: string | null;
      jobTitle?: string;
      employeeCode?: string;
    };
    InvitationView: {
      id: string;
      inviteType: string;
      email?: string | null;
      phoneE164?: string | null;
      role: string;
      managerMembershipId?: string | null;
      expiresAt: string;
      acceptedAt?: string | null;
      createdAt: string;
    };
    AcceptInviteRequest: {
      tenantId: string;
      token: string;
      fullName?: string;
      password: string;
    };
    UpdateMemberRequest: {
      role?: components['schemas']['Role'];
      status?: 'ACTIVE' | 'SUSPENDED' | 'OFFBOARDED';
      managerMembershipId?: string | null;
      jobTitle?: string;
      employeeCode?: string;
    };
    SiteView: {
      id: string;
      name: string;
      code: string;
      address?: string | null;
      latitude: number;
      longitude: number;
      radiusMeters: number;
      timezone: string;
      shiftStartLocal: string;
      lateGraceMinutes: number;
      overtimeThresholdMinutes: number;
      isActive: boolean;
      assignedStaffCount: number;
    };
    CreateSiteRequest: {
      name: string;
      code?: string;
      address?: string;
      latitude: number;
      longitude: number;
      radiusMeters?: number;
      timezone?: string;
      shiftStartLocal?: string;
      lateGraceMinutes?: number;
      overtimeThresholdMinutes?: number;
    };
    UpdateSiteRequest: {
      name?: string;
      address?: string;
      latitude?: number;
      longitude?: number;
      radiusMeters?: number;
      timezone?: string;
      shiftStartLocal?: string;
      lateGraceMinutes?: number;
      overtimeThresholdMinutes?: number;
      isActive?: boolean;
    };
    SiteAssignmentItem: {
      siteId: string;
      isPrimary: boolean;
      shiftStartOverride?: string | null;
      lateGraceOverride?: number | null;
      overtimeThresholdOverride?: number | null;
    };
    SetAssignmentsRequest: {
      assignments: components['schemas']['SiteAssignmentItem'][];
    };
    SiteAssignmentView: {
      id: string;
      membershipId: string;
      siteId: string;
      siteName: string;
      siteCode: string;
      timezone: string;
      isPrimary: boolean;
      shiftStartLocal: string;
      lateGraceMinutes: number;
      overtimeThresholdMinutes: number;
    };
    ClockRequest: {
      siteId: string;
      latitude: number;
      longitude: number;
      accuracyMeters: number;
      notes?: string;
    };
    AttendanceEventView: {
      id: string;
      membershipId: string;
      staffName: string;
      employeeCode?: string | null;
      siteId: string;
      siteName: string;
      eventType: 'CLOCK_IN' | 'CLOCK_OUT';
      pairedClockInId?: string | null;
      occurredAt: string;
      effectiveAt: string;
      localDate: string;
      distanceMeters: number;
      accuracyMeters: number;
      geofenceStatus: 'VERIFIED' | 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED';
      isLate: boolean;
      lateMinutes: number;
      workedMinutes: number;
      overtimeMinutes: number;
      notes?: string | null;
      reviewedByName?: string | null;
      reviewedAt?: string | null;
      reviewReason?: string | null;
    };
    ReviewDecisionRequest: {
      decision: 'APPROVED' | 'REJECTED';
      reason: string;
    };
    CreateCorrectionRequest: {
      attendanceEventId: string;
      proposedEffectiveAt: string;
      reason: string;
    };
    CorrectionView: {
      id: string;
      attendanceEventId: string;
      membershipId: string;
      staffName: string;
      eventType: string;
      siteName: string;
      status: 'PENDING' | 'APPROVED' | 'REJECTED';
      originalEffectiveAt: string;
      proposedEffectiveAt: string;
      reason: string;
      requestedByName: string;
      reviewedByName?: string | null;
      reviewNote?: string | null;
      reviewedAt?: string | null;
      createdAt: string;
    };
    CreateLeaveRequest: {
      leaveType: 'ANNUAL' | 'SICK' | 'UNPAID' | 'OTHER';
      startDate: string;
      endDate: string;
      reason: string;
    };
    LeaveDecisionRequest: {
      decision: 'APPROVED' | 'REJECTED';
      note?: string;
    };
    LeaveRequestView: {
      id: string;
      membershipId: string;
      staffName: string;
      managerMembershipId?: string | null;
      managerName?: string | null;
      leaveType: string;
      startDate: string;
      endDate: string;
      reason: string;
      status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELED';
      reviewedByName?: string | null;
      reviewNote?: string | null;
      reviewedAt?: string | null;
      createdAt: string;
    };
    LiveBoardSummary: {
      totalVisibleStaff: number;
      onSiteCount: number;
      lateCount: number;
      pendingReviewCount: number;
      onLeaveCount: number;
      offCount: number;
    };
    LiveStaffRow: {
      membershipId: string;
      fullName: string;
      employeeCode?: string | null;
      role: string;
      presenceStatus: string;
      siteId?: string | null;
      siteName?: string | null;
      lastEventAt?: string | null;
      isLate: boolean;
      lateMinutes: number;
      distanceMeters: number;
      geofenceStatus?: string | null;
    };
    LiveBoardResponse: {
      date: string;
      summary: components['schemas']['LiveBoardSummary'];
      rows: components['schemas']['LiveStaffRow'][];
    };
    PayrollRow: {
      membershipId: string;
      fullName: string;
      employeeCode?: string | null;
      role: string;
      siteName: string;
      shiftsCompleted: number;
      lateArrivals: number;
      totalLateMinutes: number;
      regularMinutes: number;
      overtimeMinutes: number;
      totalWorkedMinutes: number;
      pendingReviewCount: number;
    };
    TenantAuditEntryView: {
      id: string;
      actorMembershipId?: string | null;
      actorName: string;
      action: string;
      entityType: string;
      entityId: string;
      beforeStateJson?: string | null;
      afterStateJson?: string | null;
      requestId?: string | null;
      rowHash: string;
      createdAt: string;
    };
  };
}
