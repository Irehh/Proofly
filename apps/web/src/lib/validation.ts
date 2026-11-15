import { z } from 'zod';

export function normalizeOrgSlug(raw: string): string {
  return raw
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 50);
}

export const signupOrgSchema = z.object({
  orgName: z.string().trim().min(2, 'Organization name must be at least 2 characters').max(160),
  slug: z
    .string()
    .trim()
    .regex(/^[a-z0-9-]{3,50}$/, 'Slug must be 3–50 lowercase letters, numbers, or hyphens'),
  defaultTimezone: z.string().trim().min(2),
  fullName: z.string().trim().min(2, 'Full name is required').max(160),
  email: z.string().trim().email('Enter a valid work email address'),
  phoneE164: z
    .string()
    .trim()
    .optional()
    .refine((val) => !val || /^\+[1-9]\d{7,14}$/.test(val), 'Use international E.164 format (e.g. +2348012345678)'),
  password: z.string().min(10, 'Password must be at least 10 characters').max(128),
});

export const emailLoginSchema = z.object({
  email: z.string().trim().email('Enter a valid email address'),
  password: z.string().min(1, 'Password is required'),
  totpCode: z
    .string()
    .trim()
    .optional()
    .refine((val) => !val || /^\d{6}$/.test(val), 'Enter a 6-digit authenticator code'),
});

export const phonePinLoginSchema = z.object({
  phoneE164: z
    .string()
    .trim()
    .regex(/^\+[1-9]\d{7,14}$/, 'Enter phone in E.164 format (e.g. +2348012345678)'),
  pin: z.string().trim().regex(/^\d{4,8}$/, 'PIN must be 4 to 8 digits'),
  totpCode: z
    .string()
    .trim()
    .optional()
    .refine((val) => !val || /^\d{6}$/.test(val), 'Enter a 6-digit authenticator code'),
});

export const mfaEnableSchema = z.object({
  totpCode: z.string().trim().regex(/^\d{6}$/, 'Enter the 6-digit code from your authenticator app'),
});

export const forgotPasswordSchema = z.object({
  email: z.string().trim().email('Enter the work email on your Proofly account'),
});

export const resetPasswordSchema = z
  .object({
    tenantId: z.string().trim().uuid('Reset link is missing a valid workspace ID'),
    token: z.string().trim().min(12, 'Reset token is missing or malformed'),
    newPassword: z.string().min(10, 'New password must be at least 10 characters').max(128),
    confirmPassword: z.string().min(10, 'Confirm your new password'),
  })
  .refine((data) => data.newPassword === data.confirmPassword, {
    message: 'Passwords do not match',
    path: ['confirmPassword'],
  });

export const checkoutSchema = z.object({
  planCode: z.enum(['STARTER', 'GROWTH', 'ENTERPRISE']),
  billingCycle: z.enum(['MONTHLY', 'ANNUAL']),
  currency: z.enum(['NGN', 'USD', 'GBP', 'KES', 'GHS']),
  payerEmail: z.string().trim().email('Enter a valid billing email address'),
});

export const demoBookingSchema = z.object({
  fullName: z.string().trim().min(2, 'Enter your full name').max(120),
  workEmail: z.string().trim().email('Enter a valid work email'),
  companyName: z.string().trim().min(2, 'Enter your company name').max(160),
  staffCount: z.enum(['1-20', '21-75', '76-250', '251+']),
  siteCount: z.string().trim().min(1, 'Enter approximate number of sites'),
  notes: z.string().trim().max(500).optional(),
});

export const acceptInviteSchema = z
  .object({
    tenantId: z.string().trim().uuid('Invitation link is missing a valid workspace ID'),
    token: z.string().trim().min(12, 'Invitation token is missing or malformed'),
    fullName: z.string().trim().max(160).optional(),
    password: z.string().min(10, 'Password must be at least 10 characters').max(128),
    confirmPassword: z.string().min(10, 'Confirm your password'),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: 'Passwords do not match',
    path: ['confirmPassword'],
  });

export const inviteStaffSchema = z
  .object({
    inviteType: z.enum(['EMAIL', 'PHONE_PIN']),
    fullName: z.string().trim().min(2, 'Staff full name is required').max(160),
    email: z.string().trim().optional(),
    phoneE164: z.string().trim().optional(),
    initialPin: z.string().trim().optional(),
    role: z.enum(['ADMIN', 'MANAGER', 'EMPLOYEE']),
    managerMembershipId: z.string().trim().optional(),
    jobTitle: z.string().trim().max(120).optional(),
    employeeCode: z.string().trim().max(64).optional(),
  })
  .superRefine((data, ctx) => {
    if (data.inviteType === 'EMAIL') {
      if (!data.email || !z.string().email().safeParse(data.email).success) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'Enter a valid work email address',
          path: ['email'],
        });
      }
    } else {
      if (!data.phoneE164 || !/^\+[1-9]\d{7,14}$/.test(data.phoneE164)) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'Enter phone in international E.164 format (e.g. +2348012345678)',
          path: ['phoneE164'],
        });
      }
      if (!data.initialPin || !/^\d{4,8}$/.test(data.initialPin)) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'Initial PIN must be 4 to 8 digits',
          path: ['initialPin'],
        });
      }
    }
  });

export const createSiteSchema = z.object({
  name: z.string().trim().min(2, 'Site name is required').max(160),
  code: z.string().trim().max(64).optional(),
  address: z.string().trim().max(240).optional(),
  latitude: z.number().min(-90, 'Latitude must be -90..90').max(90, 'Latitude must be -90..90'),
  longitude: z.number().min(-180, 'Longitude must be -180..180').max(180, 'Longitude must be -180..180'),
  radiusMeters: z.number().int().min(25, 'Minimum geofence radius is 25m').max(20000, 'Max radius is 20,000m'),
  timezone: z.string().trim().min(2, 'Timezone is required'),
  shiftStartLocal: z.string().trim().regex(/^\d{2}:\d{2}$/, 'Use HH:mm format (e.g. 09:00)'),
  lateGraceMinutes: z.number().int().min(0).max(240),
  overtimeThresholdMinutes: z.number().int().min(60).max(1440),
});

export const leaveRequestSchema = z
  .object({
    leaveType: z.enum(['ANNUAL', 'SICK', 'UNPAID', 'OTHER']),
    startDate: z.string().trim().regex(/^\d{4}-\d{2}-\d{2}$/, 'Select a start date'),
    endDate: z.string().trim().regex(/^\d{4}-\d{2}-\d{2}$/, 'Select an end date'),
    reason: z.string().trim().min(4, 'Provide a brief reason (minimum 4 characters)').max(500),
  })
  .refine((data) => data.endDate >= data.startDate, {
    message: 'End date must be on or after start date',
    path: ['endDate'],
  });

export const correctionRequestSchema = z.object({
  attendanceEventId: z.string().trim().uuid('Select a valid attendance event'),
  proposedLocalDateTime: z.string().trim().min(10, 'Enter the proposed date and time'),
  reason: z.string().trim().min(4, 'Explain why this timestamp should be corrected').max(500),
});

export const reviewDecisionSchema = z.object({
  decision: z.enum(['APPROVED', 'REJECTED']),
  reason: z.string().trim().min(3, 'A written reason is required for audit trail').max(500),
});

export const orgSettingsSchema = z.object({
  orgName: z.string().trim().min(2, 'Organization name is required').max(160),
  defaultTimezone: z.string().trim().min(2, 'Timezone is required'),
  lateGraceMinutes: z.number().int().min(0).max(240),
  overtimeThresholdMinutes: z.number().int().min(60).max(1440),
  maxGpsAccuracyMeters: z.number().int().min(10).max(500),
});

export type SignupOrgFormValues = z.infer<typeof signupOrgSchema>;
export type EmailLoginFormValues = z.infer<typeof emailLoginSchema>;
export type PhonePinLoginFormValues = z.infer<typeof phonePinLoginSchema>;
export type MfaEnableFormValues = z.infer<typeof mfaEnableSchema>;
export type ForgotPasswordFormValues = z.infer<typeof forgotPasswordSchema>;
export type ResetPasswordFormValues = z.infer<typeof resetPasswordSchema>;
export type CheckoutFormValues = z.infer<typeof checkoutSchema>;
export type DemoBookingFormValues = z.infer<typeof demoBookingSchema>;
export type AcceptInviteFormValues = z.infer<typeof acceptInviteSchema>;
export type InviteStaffFormValues = z.infer<typeof inviteStaffSchema>;
export type CreateSiteFormValues = z.infer<typeof createSiteSchema>;
export type LeaveRequestFormValues = z.infer<typeof leaveRequestSchema>;
export type CorrectionRequestFormValues = z.infer<typeof correctionRequestSchema>;
export type ReviewDecisionFormValues = z.infer<typeof reviewDecisionSchema>;
export type OrgSettingsFormValues = z.infer<typeof orgSettingsSchema>;
