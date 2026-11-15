import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Button, Card, Input, Label } from '@proofly/ui';
import { normalizeProblemDetail, type ProblemDetail } from '@proofly/api-client';
import { useAuth } from '../lib/authStore';
import {
  emailLoginSchema,
  phonePinLoginSchema,
  type EmailLoginFormValues,
  type PhonePinLoginFormValues,
} from '../lib/validation';

export const LoginPage: React.FC = () => {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [mode, setMode] = useState<'EMAIL_PASSWORD' | 'PHONE_PIN'>('EMAIL_PASSWORD');
  const [problem, setProblem] = useState<ProblemDetail | null>(null);

  const emailForm = useForm<EmailLoginFormValues>({
    resolver: zodResolver(emailLoginSchema),
    defaultValues: { email: '', password: '', totpCode: '' },
  });

  const pinForm = useForm<PhonePinLoginFormValues>({
    resolver: zodResolver(phonePinLoginSchema),
    defaultValues: { phoneE164: '', pin: '', totpCode: '' },
  });

  const handleEmailSubmit = emailForm.handleSubmit(async (values) => {
    setProblem(null);
    try {
      await login({
        method: 'EMAIL_PASSWORD',
        email: values.email,
        password: values.password,
        totpCode: values.totpCode || undefined,
      });
      navigate('/workspace');
    } catch (err) {
      setProblem(normalizeProblemDetail(err));
    }
  });

  const handlePinSubmit = pinForm.handleSubmit(async (values) => {
    setProblem(null);
    try {
      await login({
        method: 'PHONE_PIN',
        phoneE164: values.phoneE164,
        pin: values.pin,
        totpCode: values.totpCode || undefined,
      });
      navigate('/workspace');
    } catch (err) {
      setProblem(normalizeProblemDetail(err));
    }
  });

  return (
    <div className="mx-auto max-w-6xl px-4 py-10 sm:px-6 lg:py-16">
      <div className="grid grid-cols-1 items-start gap-12 lg:grid-cols-12">
        <section className="lg:col-span-6 lg:pt-4">
          <h1 className="font-display text-3xl font-bold tracking-tight text-[#0B1F3A] sm:text-4xl text-balance">
            Verified on-site attendance for multi-site teams.
          </h1>
          <p className="mt-4 max-w-xl text-base leading-relaxed text-[#526277]">
            Sign in with your work email or staff phone PIN to manage geofenced sites, review flagged clock attempts, and export payroll-ready hours.
          </p>

          <dl className="mt-10 space-y-6 border-t border-slate-200 pt-8">
            <div>
              <dt className="font-display text-sm font-bold text-[#0B1F3A]">
                01. Database-per-tenant isolation
              </dt>
              <dd className="mt-1 text-sm text-[#526277]">
                Every workspace runs on an isolated PostgreSQL + PostGIS database bound strictly from signed JWT claims.
              </dd>
            </div>
            <div>
              <dt className="font-display text-sm font-bold text-[#0B1F3A]">
                02. Rotating sessions &amp; device control
              </dt>
              <dd className="mt-1 text-sm text-[#526277]">
                Short-lived asymmetric access tokens paired with reuse-detecting refresh families and per-device revocation.
              </dd>
            </div>
            <div>
              <dt className="font-display text-sm font-bold text-[#0B1F3A]">
                03. Offline-ready PWA clock capture
              </dt>
              <dd className="mt-1 text-sm text-[#526277]">
                Installable on Android, iOS, and desktop with encrypted location handling and idempotent sync.
              </dd>
            </div>
          </dl>
        </section>

        <section className="lg:col-span-6">
          <Card className="p-6 sm:p-8">
            <div className="flex items-center justify-between gap-4 border-b border-slate-200 pb-5">
              <div>
                <h2 className="font-display text-xl font-bold text-[#0B1F3A]">Sign in to Proofly</h2>
                <p className="mt-1 text-xs text-[#526277]">Choose administrator email or staff phone PIN.</p>
              </div>
            </div>

            <div className="mt-5 grid grid-cols-2 gap-1 rounded-lg bg-slate-100 p-1" role="tablist" aria-label="Sign in method">
              <button
                type="button"
                role="tab"
                aria-selected={mode === 'EMAIL_PASSWORD'}
                onClick={() => {
                  setMode('EMAIL_PASSWORD');
                  setProblem(null);
                }}
                className={`min-h-[40px] rounded-md px-3 py-2 text-xs font-semibold whitespace-nowrap transition-colors cursor-pointer ${
                  mode === 'EMAIL_PASSWORD'
                    ? 'bg-white text-[#0B1F3A] shadow-xs'
                    : 'text-[#526277] hover:text-[#0B1F3A]'
                }`}
              >
                Email &amp; Password
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={mode === 'PHONE_PIN'}
                onClick={() => {
                  setMode('PHONE_PIN');
                  setProblem(null);
                }}
                className={`min-h-[40px] rounded-md px-3 py-2 text-xs font-semibold whitespace-nowrap transition-colors cursor-pointer ${
                  mode === 'PHONE_PIN'
                    ? 'bg-white text-[#0B1F3A] shadow-xs'
                    : 'text-[#526277] hover:text-[#0B1F3A]'
                }`}
              >
                Phone &amp; PIN
              </button>
            </div>

            {problem && (
              <div className="mt-5">
                <Alert
                  tone="error"
                  title={problem.title}
                  description={problem.detail}
                  meta={`${problem.errorCode} · corrId ${problem.correlationId}`}
                />
              </div>
            )}

            {mode === 'EMAIL_PASSWORD' ? (
              <form onSubmit={handleEmailSubmit} className="mt-6 space-y-4" noValidate>
                <div>
                  <Label htmlFor="login-email">Work email</Label>
                  <Input
                    id="login-email"
                    type="email"
                    autoComplete="email"
                    placeholder="adaeze@company.com"
                    {...emailForm.register('email')}
                  />
                  {emailForm.formState.errors.email && (
                    <p className="mt-1 text-xs text-red-700">{emailForm.formState.errors.email.message}</p>
                  )}
                </div>

                <div>
                  <div className="mb-1.5 flex items-center justify-between gap-2">
                    <Label htmlFor="login-password" className="mb-0">Password</Label>
                    <Link
                      to="/forgot-password"
                      className="text-xs font-medium text-[#526277] hover:text-[#0B1F3A] hover:underline underline-offset-4"
                    >
                      Forgot password?
                    </Link>
                  </div>
                  <Input
                    id="login-password"
                    type="password"
                    autoComplete="current-password"
                    placeholder="Enter your password"
                    {...emailForm.register('password')}
                  />
                  {emailForm.formState.errors.password && (
                    <p className="mt-1 text-xs text-red-700">{emailForm.formState.errors.password.message}</p>
                  )}
                </div>

                <div>
                  <Label htmlFor="login-totp-email">Authenticator code (if MFA enabled)</Label>
                  <Input
                    id="login-totp-email"
                    type="text"
                    inputMode="numeric"
                    maxLength={6}
                    placeholder="6-digit code (optional)"
                    className="font-mono tabular-nums"
                    {...emailForm.register('totpCode')}
                  />
                  {emailForm.formState.errors.totpCode && (
                    <p className="mt-1 text-xs text-red-700">{emailForm.formState.errors.totpCode.message}</p>
                  )}
                </div>

                <Button
                  type="submit"
                  variant="primary"
                  size="lg"
                  className="w-full"
                  disabled={emailForm.formState.isSubmitting}
                >
                  {emailForm.formState.isSubmitting ? 'Signing in...' : 'Sign in'}
                </Button>
              </form>
            ) : (
              <form onSubmit={handlePinSubmit} className="mt-6 space-y-4" noValidate>
                <div>
                  <Label htmlFor="login-phone">Phone number (E.164)</Label>
                  <Input
                    id="login-phone"
                    type="tel"
                    autoComplete="tel"
                    placeholder="+2348012345678"
                    className="font-mono tabular-nums"
                    {...pinForm.register('phoneE164')}
                  />
                  {pinForm.formState.errors.phoneE164 && (
                    <p className="mt-1 text-xs text-red-700">{pinForm.formState.errors.phoneE164.message}</p>
                  )}
                </div>

                <div>
                  <Label htmlFor="login-pin">Staff PIN (4–8 digits)</Label>
                  <Input
                    id="login-pin"
                    type="password"
                    inputMode="numeric"
                    maxLength={8}
                    placeholder="••••"
                    className="font-mono tabular-nums"
                    {...pinForm.register('pin')}
                  />
                  {pinForm.formState.errors.pin && (
                    <p className="mt-1 text-xs text-red-700">{pinForm.formState.errors.pin.message}</p>
                  )}
                </div>

                <Button
                  type="submit"
                  variant="primary"
                  size="lg"
                  className="w-full"
                  disabled={pinForm.formState.isSubmitting}
                >
                  {pinForm.formState.isSubmitting ? 'Verifying PIN...' : 'Sign in with PIN'}
                </Button>
              </form>
            )}

            <div className="mt-6 border-t border-slate-200 pt-5 text-center text-sm text-[#526277]">
              Setting up a new company?{' '}
              <Link to="/signup" className="font-semibold text-[#0B1F3A] underline underline-offset-4 hover:text-[#09A55A]">
                Create organization workspace
              </Link>
            </div>
          </Card>
        </section>
      </div>
    </div>
  );
};
