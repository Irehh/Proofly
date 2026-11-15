import React, { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Button, Card, Input, Label } from '@proofly/ui';
import { normalizeProblemDetail, type ProblemDetail } from '@proofly/api-client';
import { useAuth } from '../lib/authStore';
import { normalizeOrgSlug, signupOrgSchema, type SignupOrgFormValues } from '../lib/validation';

const TIMEZONES = [
  'Africa/Lagos',
  'Africa/Accra',
  'Africa/Nairobi',
  'Africa/Johannesburg',
  'Europe/London',
  'UTC',
];

export const SignupOrgPage: React.FC = () => {
  const { signupOrg } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const selectedPlan = searchParams.get('plan')?.toUpperCase() ?? 'FREE';
  const selectedCycle = searchParams.get('cycle')?.toUpperCase() === 'ANNUAL' ? 'ANNUAL' : 'MONTHLY';
  const selectedCurrency = searchParams.get('currency')?.toUpperCase() ?? 'NGN';

  const [problem, setProblem] = useState<ProblemDetail | null>(null);
  const [slugTouched, setSlugTouched] = useState(false);

  const form = useForm<SignupOrgFormValues>({
    resolver: zodResolver(signupOrgSchema),
    defaultValues: {
      orgName: '',
      slug: '',
      defaultTimezone: 'Africa/Lagos',
      fullName: '',
      email: '',
      phoneE164: '',
      password: '',
    },
  });

  const onOrgNameChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    form.setValue('orgName', e.target.value, { shouldValidate: form.formState.isSubmitted });
    if (!slugTouched) {
      form.setValue('slug', normalizeOrgSlug(e.target.value), { shouldValidate: form.formState.isSubmitted });
    }
  };

  const onSubmit = form.handleSubmit(async (values) => {
    setProblem(null);
    try {
      await signupOrg({
        orgName: values.orgName,
        slug: values.slug,
        defaultTimezone: values.defaultTimezone,
        fullName: values.fullName,
        email: values.email,
        phoneE164: values.phoneE164 || undefined,
        password: values.password,
      });
      if (selectedPlan && selectedPlan !== 'FREE') {
        navigate(`/workspace?tab=billing&plan=${selectedPlan}&cycle=${selectedCycle}&currency=${selectedCurrency}`);
      } else {
        navigate('/workspace');
      }
    } catch (err) {
      setProblem(normalizeProblemDetail(err));
    }
  });

  return (
    <div className="mx-auto max-w-2xl px-4 py-10 sm:px-6 lg:py-14">
      <Card className="p-6 sm:p-8">
        <div className="border-b border-slate-200 pb-5">
          <h1 className="font-display text-2xl font-bold tracking-tight text-[#0B1F3A]">
            Provision your Proofly organization
          </h1>
          <p className="mt-1.5 text-sm text-[#526277]">
            {selectedPlan !== 'FREE'
              ? `Provisions on Free (4 members, 1 site), then opens Billing checkout for ${selectedPlan} (${selectedCycle}).`
              : 'Starts on the Free plan (4 members, 1 geofenced site) with a dedicated tenant database.'}
          </p>
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

        <form onSubmit={onSubmit} className="mt-6 space-y-5" noValidate>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <Label htmlFor="signup-org-name">Organization name</Label>
              <Input
                id="signup-org-name"
                placeholder="Lagos Port Operations"
                {...form.register('orgName')}
                onChange={onOrgNameChange}
              />
              {form.formState.errors.orgName && (
                <p className="mt-1 text-xs text-red-700">{form.formState.errors.orgName.message}</p>
              )}
            </div>

            <div>
              <Label htmlFor="signup-slug">Workspace slug</Label>
              <Input
                id="signup-slug"
                placeholder="lagos-port-ops"
                className="font-mono"
                {...form.register('slug', {
                  onChange: () => setSlugTouched(true),
                })}
              />
              {form.formState.errors.slug && (
                <p className="mt-1 text-xs text-red-700">{form.formState.errors.slug.message}</p>
              )}
            </div>
          </div>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <Label htmlFor="signup-fullname">Owner full name</Label>
              <Input id="signup-fullname" placeholder="Adaeze Okafor" {...form.register('fullName')} />
              {form.formState.errors.fullName && (
                <p className="mt-1 text-xs text-red-700">{form.formState.errors.fullName.message}</p>
              )}
            </div>

            <div>
              <Label htmlFor="signup-tz">Default site timezone</Label>
              <select
                id="signup-tz"
                className="w-full min-h-[44px] rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-sm text-[#0B1F3A] focus:border-[#0B1F3A] focus:outline-none focus:ring-2 focus:ring-[#0B1F3A]/15"
                {...form.register('defaultTimezone')}
              >
                {TIMEZONES.map((tz) => (
                  <option key={tz} value={tz}>
                    {tz}
                  </option>
                ))}
              </select>
            </div>
          </div>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <Label htmlFor="signup-email">Owner work email</Label>
              <Input
                id="signup-email"
                type="email"
                autoComplete="email"
                placeholder="adaeze@lagosport.com"
                {...form.register('email')}
              />
              {form.formState.errors.email && (
                <p className="mt-1 text-xs text-red-700">{form.formState.errors.email.message}</p>
              )}
            </div>

            <div>
              <Label htmlFor="signup-phone">Phone (optional, E.164)</Label>
              <Input
                id="signup-phone"
                type="tel"
                placeholder="+2348012345678"
                className="font-mono tabular-nums"
                {...form.register('phoneE164')}
              />
              {form.formState.errors.phoneE164 && (
                <p className="mt-1 text-xs text-red-700">{form.formState.errors.phoneE164.message}</p>
              )}
            </div>
          </div>

          <div>
            <Label htmlFor="signup-password">Password (minimum 10 characters)</Label>
            <Input
              id="signup-password"
              type="password"
              autoComplete="new-password"
              placeholder="Choose a strong password"
              {...form.register('password')}
            />
            {form.formState.errors.password && (
              <p className="mt-1 text-xs text-red-700">{form.formState.errors.password.message}</p>
            )}
          </div>

          <Button
            type="submit"
            variant="primary"
            size="lg"
            className="w-full"
            disabled={form.formState.isSubmitting}
          >
            {form.formState.isSubmitting ? 'Provisioning tenant database...' : 'Create workspace'}
          </Button>
        </form>

        <div className="mt-6 border-t border-slate-200 pt-5 text-center text-sm text-[#526277]">
          Already have an account?{' '}
          <Link to="/login" className="font-semibold text-[#0B1F3A] underline underline-offset-4 hover:text-[#09A55A]">
            Sign in
          </Link>
        </div>
      </Card>
    </div>
  );
};
