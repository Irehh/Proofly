import test from 'node:test';
import assert from 'node:assert/strict';
import {
  emailLoginSchema,
  mfaEnableSchema,
  normalizeOrgSlug,
  phonePinLoginSchema,
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
