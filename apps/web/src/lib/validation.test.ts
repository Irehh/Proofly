import test from 'node:test';
import assert from 'node:assert/strict';
import {
  checkoutSchema,
  demoBookingSchema,
  emailLoginSchema,
  forgotPasswordSchema,
  mfaEnableSchema,
  normalizeOrgSlug,
  phonePinLoginSchema,
  resetPasswordSchema,
  signupOrgSchema,
} from './validation';

test('normalizeOrgSlug converts organization name into URL-safe lowercase slug', () => {
  assert.equal(normalizeOrgSlug('  Alpha Logistics Ltd.  '), 'alpha-logistics-ltd');
  assert.equal(normalizeOrgSlug('Kano__Solar & Power!'), 'kano-solar-power');
});

test('signupOrgSchema rejects weak passwords and invalid E.164 phones', () => {
  const res = signupOrgSchema.safeParse({
    orgName: 'Proofly Ops',
    slug: 'proofly-ops',
    defaultTimezone: 'Africa/Lagos',
    fullName: 'Adaeze Okafor',
    email: 'adaeze@proofly.com',
    phoneE164: '08012345678',
    password: 'short',
  });
  assert.equal(res.success, false);
});

test('signupOrgSchema accepts valid organization signup payload', () => {
  const res = signupOrgSchema.safeParse({
    orgName: 'Proofly Ops',
    slug: 'proofly-ops',
    defaultTimezone: 'Africa/Lagos',
    fullName: 'Adaeze Okafor',
    email: 'adaeze@proofly.com',
    phoneE164: '+2348012345678',
    password: 'StrongPassword!2026',
  });
  assert.equal(res.success, true);
});

test('phonePinLoginSchema requires E.164 phone and 4-8 digit numeric PIN', () => {
  assert.equal(
    phonePinLoginSchema.safeParse({ phoneE164: '+2348012345678', pin: '4829' }).success,
    true
  );
  assert.equal(
    phonePinLoginSchema.safeParse({ phoneE164: '+2348012345678', pin: 'ab12' }).success,
    false
  );
});

test('emailLoginSchema and mfaEnableSchema validate properly', () => {
  assert.equal(emailLoginSchema.safeParse({ email: 'a@b.com', password: 'secret' }).success, true);
  assert.equal(mfaEnableSchema.safeParse({ totpCode: '123456' }).success, true);
  assert.equal(mfaEnableSchema.safeParse({ totpCode: '12345' }).success, false);
});

test('forgotPasswordSchema and resetPasswordSchema enforce password matching and UUID tenantId', () => {
  assert.equal(forgotPasswordSchema.safeParse({ email: 'admin@proofly.com' }).success, true);
  assert.equal(forgotPasswordSchema.safeParse({ email: 'bad' }).success, false);

  const mismatch = resetPasswordSchema.safeParse({
    tenantId: '550e8400-e29b-41d4-a716-446655440000',
    token: 'abcdefghijklmnop',
    newPassword: 'StrongPassword123',
    confirmPassword: 'DifferentPassword123',
  });
  assert.equal(mismatch.success, false);

  const valid = resetPasswordSchema.safeParse({
    tenantId: '550e8400-e29b-41d4-a716-446655440000',
    token: 'abcdefghijklmnop',
    newPassword: 'StrongPassword123',
    confirmPassword: 'StrongPassword123',
  });
  assert.equal(valid.success, true);
});

test('checkoutSchema and demoBookingSchema validate inputs', () => {
  assert.equal(
    checkoutSchema.safeParse({
      planCode: 'GROWTH',
      billingCycle: 'ANNUAL',
      currency: 'USD',
      payerEmail: 'finance@company.ng',
    }).success,
    true
  );
  assert.equal(
    checkoutSchema.safeParse({
      planCode: 'TEAM',
      billingCycle: 'ANNUAL',
      currency: 'NGN',
      payerEmail: 'finance@company.ng',
    }).success,
    false
  );
  assert.equal(
    demoBookingSchema.safeParse({
      fullName: 'Tunde Bello',
      workEmail: 'tunde@apapaport.ng',
      companyName: 'Apapa Port Haulage',
      staffCount: '76-250',
      siteCount: '12',
    }).success,
    true
  );
});

import {
  acceptInviteSchema,
  correctionRequestSchema,
  createSiteSchema,
  inviteStaffSchema,
  leaveRequestSchema,
  orgSettingsSchema,
  reviewDecisionSchema,
} from './validation';

test('inviteStaffSchema enforces email vs E.164 phone + 4-8 digit PIN rules', () => {
  assert.equal(
    inviteStaffSchema.safeParse({
      inviteType: 'EMAIL',
      fullName: 'Chidi Eze',
      email: 'chidi@company.ng',
      role: 'MANAGER',
    }).success,
    true
  );
  assert.equal(
    inviteStaffSchema.safeParse({
      inviteType: 'PHONE_PIN',
      fullName: 'Bala Musa',
      phoneE164: '+2348011122233',
      initialPin: '4829',
      role: 'EMPLOYEE',
    }).success,
    true
  );
  assert.equal(
    inviteStaffSchema.safeParse({
      inviteType: 'PHONE_PIN',
      fullName: 'Bala Musa',
      phoneE164: '08011122233',
      initialPin: '12',
      role: 'EMPLOYEE',
    }).success,
    false
  );
});

test('acceptInviteSchema checks password match and UUID tenantId', () => {
  assert.equal(
    acceptInviteSchema.safeParse({
      tenantId: '550e8400-e29b-41d4-a716-446655440000',
      token: 'invite_tok_1234567890',
      fullName: 'Chidi Eze',
      password: 'ValidPassword!2026',
      confirmPassword: 'ValidPassword!2026',
    }).success,
    true
  );
  assert.equal(
    acceptInviteSchema.safeParse({
      tenantId: '550e8400-e29b-41d4-a716-446655440000',
      token: 'invite_tok_1234567890',
      password: 'ValidPassword!2026',
      confirmPassword: 'MismatchPassword!',
    }).success,
    false
  );
});

test('createSiteSchema, leaveRequestSchema, correctionRequestSchema, reviewDecisionSchema, orgSettingsSchema validate boundaries', () => {
  assert.equal(
    createSiteSchema.safeParse({
      name: 'Apapa Gate B',
      latitude: 6.4501,
      longitude: 3.3622,
      radiusMeters: 150,
      timezone: 'Africa/Lagos',
      shiftStartLocal: '08:00',
      lateGraceMinutes: 15,
      overtimeThresholdMinutes: 480,
    }).success,
    true
  );
  assert.equal(
    createSiteSchema.safeParse({
      name: 'Invalid',
      latitude: 120,
      longitude: 3.36,
      radiusMeters: 10,
      timezone: 'Africa/Lagos',
      shiftStartLocal: '8am',
      lateGraceMinutes: 15,
      overtimeThresholdMinutes: 480,
    }).success,
    false
  );
  assert.equal(
    leaveRequestSchema.safeParse({
      leaveType: 'ANNUAL',
      startDate: '2026-11-20',
      endDate: '2026-11-18',
      reason: 'Family travel',
    }).success,
    false
  );
  assert.equal(
    correctionRequestSchema.safeParse({
      attendanceEventId: '550e8400-e29b-41d4-a716-446655440000',
      proposedLocalDateTime: '2026-11-15T08:05',
      reason: 'Security gate scanner delayed badge entry',
    }).success,
    true
  );
  assert.equal(reviewDecisionSchema.safeParse({ decision: 'APPROVED', reason: 'ok' }).success, false);
  assert.equal(reviewDecisionSchema.safeParse({ decision: 'APPROVED', reason: 'Verified with supervisor' }).success, true);
  assert.equal(
    orgSettingsSchema.safeParse({
      orgName: 'Apapa Haulage',
      defaultTimezone: 'Africa/Lagos',
      lateGraceMinutes: 10,
      overtimeThresholdMinutes: 480,
      maxGpsAccuracyMeters: 100,
    }).success,
    true
  );
});
