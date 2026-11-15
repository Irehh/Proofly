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

export type SignupOrgFormValues = z.infer<typeof signupOrgSchema>;
export type EmailLoginFormValues = z.infer<typeof emailLoginSchema>;
export type PhonePinLoginFormValues = z.infer<typeof phonePinLoginSchema>;
export type MfaEnableFormValues = z.infer<typeof mfaEnableSchema>;
