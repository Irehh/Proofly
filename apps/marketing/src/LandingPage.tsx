import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Check,
  ChevronDown,
  Clock,
  FileSpreadsheet,
  Lock,
  MailCheck,
  MapPin,
  ShieldCheck,
  Smartphone,
} from 'lucide-react';
import {
  Alert,
  BrandMark,
  Button,
  Card,
  GATEWAY_DISPLAY_NAME,
  Input,
  Label,
  PROOFLY_PLANS,
  getDisplayPlanPrice,
  getPaymentGatewayForCurrency,
  haversineDistanceMeters,
  type BillingCycle,
  type SupportedCurrency,
} from '@proofly/ui';
import { demoBookingSchema, type DemoBookingFormValues } from '../../web/src/lib/validation';

const HERO_IMAGE = '/src/assets/images/hero_operations_site_1794731336735.jpg';
const INDUSTRY_IMAGE = '/src/assets/images/industry_multi_site_1794731335807.jpg';

const CURRENCIES: { code: SupportedCurrency; label: string }[] = [
  { code: 'NGN', label: 'NGN (₦)' },
  { code: 'USD', label: 'USD ($)' },
  { code: 'GBP', label: 'GBP (£)' },
  { code: 'KES', label: 'KES (KSh)' },
  { code: 'GHS', label: 'GHS (GH₵)' },
];

const SITE_CENTER = { name: 'Apapa Port Terminal Gate B', lat: 6.4474, lon: 3.3641, radiusMeters: 100 };

const CHECKIN_SCENARIOS = [
  {
    id: 'inside',
    label: 'Inside Site Radius',
    staffName: 'Chidi Nwosu',
    role: 'Shift Supervisor',
    lat: 6.44765,
    lon: 3.36425,
    accuracyMeters: 11,
  },
  {
    id: 'outside',
    label: 'Outside Perimeter',
    staffName: 'Bola Adeyemi',
    role: 'Dispatch Rider',
    lat: 6.4491,
    lon: 3.3653,
    accuracyMeters: 16,
  },
] as const;

const FAQ_ITEMS = [
  {
    q: 'What happens if a staff member clocks in slightly outside the geofence?',
    a: 'Proofly never silently drops a clock-in attempt. The server computes the exact PostGIS distance from the site center, records the attempt as PENDING_REVIEW with GPS accuracy metadata, and routes it to the assigned site manager for approve-or-reject resolution with a required reason.',
  },
  {
    q: 'Can field staff sign in without a corporate email address?',
    a: 'Yes. Administrators can onboard staff with a phone number in E.164 format and a 4–8 digit PIN. PIN logins are protected with Argon2id hashing, per-IP and per-phone Redis rate limiting, and automatic temporary lockout after repeated failures.',
  },
  {
    q: 'How does Proofly isolate data between different companies?',
    a: 'Proofly enforces a database-per-tenant architecture. Every organization gets its own PostgreSQL + PostGIS database bound strictly from signed RS256 JWT tenant claims (`tid`), never from client-supplied headers. LRU-capped HikariCP pools and PgBouncer support keep connection counts bounded.',
  },
  {
    q: 'Does clock-in work when cellular data drops inside a warehouse or site?',
    a: 'Yes. Proofly is an installable Progressive Web App (PWA) with Workbox offline caching and idempotent client request keys, so staff can record attendance from Android, iOS, or desktop browsers without double-submitting when connectivity returns.',
  },
  {
    q: 'How are multi-currency payments routed and accounted for across Paystack and Stripe?',
    a: 'Checkout requests require an Idempotency-Key header and route automatically by currency: NGN, GHS, and KES settle through Paystack (HMAC-SHA512 webhooks), while USD and GBP settle through Stripe Checkout (HMAC-SHA256 Stripe-Signature webhooks). All settlements post balanced double-entry journal lines in BIGINT minor units inside PostgreSQL.',
  },
  {
    q: 'How reliable are verification, password reset, and invite emails?',
    a: 'All emails and SMS alerts are written to a transactional outbox table, dispatched via ShedLock workers with stale-lock recovery and exponential backoff, and inspectable in the Workspace Outbox tab where admins can replay dead-letter deliveries.',
  },
];

export const LandingPage: React.FC = () => {
  const [heroImgFailed, setHeroImgFailed] = useState(false);
  const [industryImgFailed, setIndustryImgFailed] = useState(false);

  const [scenarioId, setScenarioId] = useState<'inside' | 'outside'>('inside');
  const [lastCheckResult, setLastCheckResult] = useState<{
    status: 'ACCEPTED' | 'PENDING_REVIEW';
    distanceMeters: number;
    timestamp: string;
    staffName: string;
  } | null>(null);

  const [billingCycle, setBillingCycle] = useState<BillingCycle>('ANNUAL');
  const [currency, setCurrency] = useState<SupportedCurrency>('NGN');
  const [openFaqIndex, setOpenFaqIndex] = useState<number>(0);
  const [demoSubmittedRef, setDemoSubmittedRef] = useState<{
    refId: string;
    companyName: string;
    workEmail: string;
  } | null>(null);

  const activeScenario = CHECKIN_SCENARIOS.find((s) => s.id === scenarioId) ?? CHECKIN_SCENARIOS[0];
  const liveDistance = haversineDistanceMeters(
    SITE_CENTER.lat,
    SITE_CENTER.lon,
    activeScenario.lat,
    activeScenario.lon
  );

  const runSimulatorCheck = () => {
    const inside = liveDistance <= SITE_CENTER.radiusMeters;
    setLastCheckResult({
      status: inside ? 'ACCEPTED' : 'PENDING_REVIEW',
      distanceMeters: liveDistance,
      timestamp: new Date().toISOString().replace('T', ' ').slice(0, 19) + ' UTC',
      staffName: activeScenario.staffName,
    });
  };

  const demoForm = useForm<DemoBookingFormValues>({
    resolver: zodResolver(demoBookingSchema),
    defaultValues: {
      fullName: '',
      workEmail: '',
      companyName: '',
      staffCount: '21-75',
      siteCount: '5',
      notes: '',
    },
  });

  const onDemoSubmit = demoForm.handleSubmit((values) => {
    const refId = `DEMO-${Math.random().toString(36).slice(2, 8).toUpperCase()}`;
    setDemoSubmittedRef({
      refId,
      companyName: values.companyName,
      workEmail: values.workEmail,
    });
    demoForm.reset();
  });

  return (
    <div className="bg-[#F7F9FC] text-[#0B1F3A]">
      {/* HERO SECTION */}
      <section className="border-b border-slate-200 bg-white">
        <div className="mx-auto max-w-6xl px-4 py-12 sm:px-6 lg:py-20">
          <div className="grid grid-cols-1 items-stretch gap-12 lg:grid-cols-12">
            <div className="flex flex-col justify-between lg:col-span-6">
              <div>
                <p className="text-xs font-medium tracking-wide text-[#526277]">
                  PostGIS WGS84 Geofencing · Offline-Ready PWA · Multi-Tenant Isolation
                </p>
                <h1
                  className="mt-4 font-display font-extrabold tracking-tight text-[#0B1F3A] text-balance"
                  style={{ fontSize: 'clamp(2.25rem, 4vw, 3.4rem)', lineHeight: 1.08 }}
                >
                  Verify every shift on site, without chasing spreadsheets.
                </h1>
                <p className="mt-5 max-w-xl text-base leading-relaxed text-[#526277]">
                  Proofly gives multi-site teams server-verified geofence clock-ins, manager review queues for out-of-fence attempts, leave approvals, and payroll-ready CSV exports — isolated per organization.
                </p>

                <div className="mt-8 flex flex-wrap items-center gap-4">
                  <Link to="/signup">
                    <Button variant="primary" size="lg">
                      Start free workspace
                    </Button>
                  </Link>
                  <a
                    href="#demo"
                    className="inline-flex min-h-[48px] items-center rounded-lg border border-slate-300 bg-white px-5 py-3 text-sm font-medium text-[#0B1F3A] transition-colors hover:bg-slate-50 whitespace-nowrap shrink-0"
                  >
                    Book a walkthrough
                  </a>
                </div>
              </div>

              <dl className="mt-10 grid grid-cols-3 gap-6 border-t border-slate-200 pt-6">
                <div>
                  <dt className="text-xs text-[#526277]">Free tier</dt>
                  <dd className="mt-1 font-display text-lg font-bold tabular-nums text-[#0B1F3A]">
                    4 staff · 1 site
                  </dd>
                </div>
                <div>
                  <dt className="text-xs text-[#526277]">Annual billing</dt>
                  <dd className="mt-1 font-display text-lg font-bold tabular-nums text-[#09A55A]">
                    10% off
                  </dd>
                </div>
                <div>
                  <dt className="text-xs text-[#526277]">Currencies &amp; gateways</dt>
                  <dd className="mt-1 font-display text-lg font-bold text-[#0B1F3A]">
                    5 currencies · 2 gateways
                  </dd>
                </div>
              </dl>
            </div>

            <div className="lg:col-span-6 flex flex-col justify-between overflow-hidden rounded-xl border border-slate-200 bg-[#F7F9FC]">
              <div className="relative aspect-[16/9] w-full overflow-hidden bg-[#0B1F3A]">
                {!heroImgFailed ? (
                  <img
                    src={HERO_IMAGE}
                    alt="Operations supervisor verifying shift arrival at a multi-bay logistics yard"
                    referrerPolicy="no-referrer"
                    onError={() => setHeroImgFailed(true)}
                    className="h-full w-full object-cover"
                  />
                ) : (
                  <div className="flex h-full w-full items-center justify-center bg-gradient-to-br from-[#0B1F3A] to-[#132C4E] p-6 text-white">
                    <div className="flex items-center gap-3">
                      <MapPin className="h-7 w-7 text-[#09A55A]" />
                      <span className="font-display text-base font-bold">
                        PostGIS Site Perimeter Verification
                      </span>
                    </div>
                  </div>
                )}
                <div className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-[#0B1F3A]/90 via-[#0B1F3A]/45 to-transparent px-5 py-4 text-white">
                  <p className="font-mono text-xs text-slate-200 tabular-nums">
                    SITE: {SITE_CENTER.name} · RADIUS {SITE_CENTER.radiusMeters}m · Africa/Lagos
                  </p>
                </div>
              </div>

              <div className="p-5 sm:p-6">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div>
                    <h2 className="font-display text-sm font-bold text-[#0B1F3A]">
                      Interactive Geofence Clock-In Check
                    </h2>
                    <p className="text-xs text-[#526277]">
                      Switch coordinate scenarios to test how Proofly handles perimeter edges.
                    </p>
                  </div>
                  <div className="inline-flex rounded-lg bg-slate-200/70 p-1" role="group" aria-label="Geofence test scenario">
                    {CHECKIN_SCENARIOS.map((sc) => (
                      <button
                        key={sc.id}
                        type="button"
                        onClick={() => setScenarioId(sc.id)}
                        className={`min-h-[36px] rounded-md px-3 py-1.5 text-xs font-semibold whitespace-nowrap transition-colors cursor-pointer ${
                          scenarioId === sc.id
                            ? 'bg-white text-[#0B1F3A] shadow-xs'
                            : 'text-[#526277] hover:text-[#0B1F3A]'
                        }`}
                      >
                        {sc.label}
                      </button>
                    ))}
                  </div>
                </div>

                <div className="mt-4 grid grid-cols-3 gap-4 border-y border-slate-200 py-3 text-xs">
                  <div>
                    <span className="block text-[#526277]">Staff member</span>
                    <span className="mt-0.5 block font-semibold text-[#0B1F3A]">
                      {activeScenario.staffName}
                    </span>
                  </div>
                  <div>
                    <span className="block text-[#526277]">Computed distance</span>
                    <span className="mt-0.5 block font-mono font-semibold tabular-nums text-[#0B1F3A]">
                      {liveDistance}m / {SITE_CENTER.radiusMeters}m
                    </span>
                  </div>
                  <div>
                    <span className="block text-[#526277]">GPS accuracy</span>
                    <span className="mt-0.5 block font-mono font-semibold tabular-nums text-[#0B1F3A]">
                      ±{activeScenario.accuracyMeters}m
                    </span>
                  </div>
                </div>

                <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
                  <div className="text-xs">
                    {lastCheckResult ? (
                      <span className="font-mono text-[#0B1F3A] tabular-nums">
                        Result:{' '}
                        <strong
                          className={
                            lastCheckResult.status === 'ACCEPTED' ? 'text-[#09A55A]' : 'text-amber-700'
                          }
                        >
                          {lastCheckResult.status}
                        </strong>{' '}
                        · {lastCheckResult.distanceMeters}m · {lastCheckResult.timestamp}
                      </span>
                    ) : (
                      <span className="text-[#526277]">
                        Out-of-fence attempts route to manager review.
                      </span>
                    )}
                  </div>
                  <Button variant="secondary" size="sm" onClick={runSimulatorCheck}>
                    Run Clock-In Check
                  </Button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* TRUST STRIP */}
      <section className="border-b border-slate-200 bg-[#F7F9FC] py-7">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-6 px-4 sm:px-6">
          <span className="text-xs font-semibold uppercase tracking-wider text-[#526277]">
            Built for distributed field &amp; facility teams
          </span>
          <div className="flex flex-wrap items-center gap-x-8 gap-y-3 font-display text-sm font-bold text-[#0B1F3A]/80">
            <span>Apapa Port Haulage</span>
            <span>Kano Cold-Chain</span>
            <span>Abuja Metro Builders</span>
            <span>Lekki Retail Group</span>
            <span>Tema Logistics Hub</span>
          </div>
        </div>
      </section>

      {/* FEATURES / HOW IT WORKS */}
      <section id="features" className="mx-auto max-w-6xl px-4 py-16 sm:px-6 lg:py-20">
        <div className="max-w-2xl">
          <h2 className="font-display text-2xl font-bold tracking-tight text-[#0B1F3A] sm:text-3xl text-balance">
            Audit-grade attendance architecture from clock-in to payroll CSV.
          </h2>
          <p className="mt-3 text-base text-[#526277]">
            Every check-in, leave approval, attendance correction, and billing charge is traceable to who acted, when it occurred, and why.
          </p>
        </div>

        <div className="mt-12 grid grid-cols-1 gap-6 md:grid-cols-12">
          <Card className="p-6 md:col-span-7">
            <div className="flex items-start justify-between gap-4">
              <div>
                <p className="text-xs font-semibold text-[#09A55A]">01. Server-side geofencing</p>
                <h3 className="mt-1.5 font-display text-xl font-bold text-[#0B1F3A]">
                  PostGIS radius enforcement with honest exception handling
                </h3>
              </div>
              <MapPin className="h-5 w-5 shrink-0 text-[#0B1F3A]" />
            </div>
            <p className="mt-3 text-sm leading-relaxed text-[#526277]">
              Each site defines its WGS84 center coordinates, permitted radius, and local timezone. When a worker clocks in from the mobile PWA outside the perimeter, Proofly stores the event as <span className="font-mono text-xs text-[#0B1F3A]">PENDING_REVIEW</span> and alerts their direct manager.
            </p>
            <div className="mt-5 divide-y divide-slate-100 border-t border-slate-200 text-xs">
              <div className="flex items-center justify-between py-2.5">
                <span className="font-medium text-[#0B1F3A]">Ikeja Distribution Yard</span>
                <span className="font-mono text-[#526277] tabular-nums">120m radius · Africa/Lagos · 42 on site</span>
              </div>
              <div className="flex items-center justify-between py-2.5">
                <span className="font-medium text-[#0B1F3A]">Port Harcourt Terminal 2</span>
                <span className="font-mono text-[#526277] tabular-nums">200m radius · Africa/Lagos · 3 flagged</span>
              </div>
            </div>
          </Card>

          <Card className="p-6 md:col-span-5">
            <div className="flex items-start justify-between gap-4">
              <div>
                <p className="text-xs font-semibold text-[#09A55A]">02. Flexible staff access</p>
                <h3 className="mt-1.5 font-display text-xl font-bold text-[#0B1F3A]">
                  Email passwords for admins, Phone + PIN for site crews
                </h3>
              </div>
              <Smartphone className="h-5 w-5 shrink-0 text-[#0B1F3A]" />
            </div>
            <p className="mt-3 text-sm leading-relaxed text-[#526277]">
              Invite office managers by email or onboard shift workers using E.164 phone numbers and 4–8 digit PINs. Roles (<span className="font-mono text-xs">SUPER_ADMIN</span>, <span className="font-mono text-xs">ADMIN</span>, <span className="font-mono text-xs">MANAGER</span>, <span className="font-mono text-xs">EMPLOYEE</span>) scope visibility automatically.
            </p>
          </Card>

          <Card className="p-6 md:col-span-5">
            <div className="flex items-start justify-between gap-4">
              <div>
                <p className="text-xs font-semibold text-[#09A55A]">03. Corrections &amp; payroll</p>
                <h3 className="mt-1.5 font-display text-xl font-bold text-[#0B1F3A]">
                  Immutable who/when/why log &amp; overtime CSV
                </h3>
              </div>
              <FileSpreadsheet className="h-5 w-5 shrink-0 text-[#0B1F3A]" />
            </div>
            <p className="mt-3 text-sm leading-relaxed text-[#526277]">
              Apply late grace windows and overtime multipliers per site, route leave requests to the right manager, record justified attendance edits in an append-only SHA-256 hash chain, and export clean payroll CSVs.
            </p>
          </Card>

          <Card className="p-6 md:col-span-7">
            <div className="flex items-start justify-between gap-4">
              <div>
                <p className="text-xs font-semibold text-[#09A55A]">04. Notifications &amp; billing ledger</p>
                <h3 className="mt-1.5 font-display text-xl font-bold text-[#0B1F3A]">
                  Transactional outbox with retries &amp; gateway-routed double-entry ledger
                </h3>
              </div>
              <MailCheck className="h-5 w-5 shrink-0 text-[#0B1F3A]" />
            </div>
            <p className="mt-3 text-sm leading-relaxed text-[#526277]">
              Verification links, password resets, shift invites, and receipts flow through a ShedLock outbox with stale-lock recovery and dead-letter replay. Subscriptions route by currency across Paystack and Stripe into a balanced PostgreSQL ledger.
            </p>
            <div className="mt-5 grid grid-cols-3 gap-4 border-t border-slate-200 pt-4 text-xs">
              <div>
                <span className="block text-[#526277]">Auth security</span>
                <span className="mt-0.5 block font-semibold text-[#0B1F3A]">RS256 + TOTP MFA</span>
              </div>
              <div>
                <span className="block text-[#526277]">Ledger precision</span>
                <span className="mt-0.5 block font-semibold text-[#0B1F3A]">BIGINT minor units</span>
              </div>
              <div>
                <span className="block text-[#526277]">Tenancy boundary</span>
                <span className="mt-0.5 block font-semibold text-[#0B1F3A]">1 Postgres DB / org</span>
              </div>
            </div>
          </Card>
        </div>
      </section>

      {/* INDUSTRIES */}
      <section id="industries" className="border-y border-slate-200 bg-white py-16 lg:py-20">
        <div className="mx-auto max-w-6xl px-4 sm:px-6">
          <div className="grid grid-cols-1 items-stretch gap-10 lg:grid-cols-12">
            <div className="lg:col-span-5 overflow-hidden rounded-xl border border-slate-200 bg-slate-100">
              {!industryImgFailed ? (
                <img
                  src={INDUSTRY_IMAGE}
                  alt="Modern multi-site commercial warehouse and operations campus"
                  referrerPolicy="no-referrer"
                  onError={() => setIndustryImgFailed(true)}
                  className="h-full w-full object-cover"
                />
              ) : (
                <div className="flex h-full min-h-[280px] items-center justify-center bg-[#0B1F3A] p-8 text-white">
                  <span className="font-display text-lg font-bold">Multi-Site Industry Coverage</span>
                </div>
              )}
            </div>

            <div className="lg:col-span-7 flex flex-col justify-between">
              <div>
                <h2 className="font-display text-2xl font-bold tracking-tight text-[#0B1F3A] sm:text-3xl text-balance">
                  Built for operations where physical presence drives payroll.
                </h2>
                <p className="mt-3 text-sm leading-relaxed text-[#526277]">
                  Replace shared WhatsApp check-ins and tamper-prone paper registers with verifiable site timestamps across every branch or project site.
                </p>
              </div>

              <div className="mt-8 grid grid-cols-1 gap-6 sm:grid-cols-2">
                <div className="border-t border-slate-200 pt-4">
                  <h3 className="font-display text-base font-bold text-[#0B1F3A]">Logistics &amp; Haulage Yards</h3>
                  <p className="mt-1.5 text-sm text-[#526277]">
                    Track depot loaders, fleet coordinators, and dispatch drivers across port terminals and regional hubs.
                  </p>
                </div>
                <div className="border-t border-slate-200 pt-4">
                  <h3 className="font-display text-base font-bold text-[#0B1F3A]">Construction &amp; Engineering</h3>
                  <p className="mt-1.5 text-sm text-[#526277]">
                    Spin up temporary project geofences with site-specific shift hours and supervisor leave approval routing.
                  </p>
                </div>
                <div className="border-t border-slate-200 pt-4">
                  <h3 className="font-display text-base font-bold text-[#0B1F3A]">Multi-Branch Retail &amp; Clinics</h3>
                  <p className="mt-1.5 text-sm text-[#526277]">
                    Monitor opening-time punctuality across dozens of storefronts without installing biometric hardware.
                  </p>
                </div>
                <div className="border-t border-slate-200 pt-4">
                  <h3 className="font-display text-base font-bold text-[#0B1F3A]">Manufacturing &amp; Warehousing</h3>
                  <p className="mt-1.5 text-sm text-[#526277]">
                    Enforce late thresholds, night-shift overtime calculations, and export verified hours directly to payroll.
                  </p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* PRICING SECTION */}
      <section id="pricing" className="mx-auto max-w-6xl px-4 py-16 sm:px-6 lg:py-20">
        <div className="flex flex-wrap items-end justify-between gap-6">
          <div className="max-w-xl">
            <h2 className="font-display text-2xl font-bold tracking-tight text-[#0B1F3A] sm:text-3xl text-balance">
              Four simple plans built to grow with your sites.
            </h2>
            <p className="mt-2 text-sm text-[#526277]">
              Start free with 4 members and 1 site. Pay in NGN, GHS, or KES via Paystack, or USD and GBP via Stripe — with 10% off annual billing.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <div
              className="inline-flex rounded-lg border border-slate-200 bg-white p-1"
              role="group"
              aria-label="Billing interval"
            >
              <button
                type="button"
                aria-pressed={billingCycle === 'MONTHLY'}
                onClick={() => setBillingCycle('MONTHLY')}
                className={`min-h-[38px] rounded-md px-3.5 py-1.5 text-xs font-semibold whitespace-nowrap transition-colors cursor-pointer ${
                  billingCycle === 'MONTHLY'
                    ? 'bg-[#0B1F3A] text-white'
                    : 'text-[#526277] hover:text-[#0B1F3A]'
                }`}
              >
                Monthly
              </button>
              <button
                type="button"
                aria-pressed={billingCycle === 'ANNUAL'}
                onClick={() => setBillingCycle('ANNUAL')}
                className={`min-h-[38px] rounded-md px-3.5 py-1.5 text-xs font-semibold whitespace-nowrap transition-colors cursor-pointer ${
                  billingCycle === 'ANNUAL'
                    ? 'bg-[#09A55A] text-white'
                    : 'text-[#526277] hover:text-[#0B1F3A]'
                }`}
              >
                Annual (Save 10%)
              </button>
            </div>

            <label className="sr-only" htmlFor="pricing-currency-select">Settlement currency</label>
            <select
              id="pricing-currency-select"
              value={currency}
              onChange={(e) => setCurrency(e.target.value as SupportedCurrency)}
              className="min-h-[46px] rounded-lg border border-slate-200 bg-white px-3 py-2 font-mono text-xs font-semibold text-[#0B1F3A] focus:border-[#0B1F3A] focus:outline-none"
            >
              {CURRENCIES.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.label}
                </option>
              ))}
            </select>
          </div>
        </div>

        <p className="mt-3 font-mono text-xs text-[#526277]">
          Settlement currency: {currency} · Routed gateway: {GATEWAY_DISPLAY_NAME[getPaymentGatewayForCurrency(currency)]} · Exact integer minor-unit pricing
        </p>

        <div className="mt-8 grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-4">
          {PROOFLY_PLANS.map((plan) => {
            const price = getDisplayPlanPrice(plan, billingCycle, currency);
            const isFree = price.totalMinor === 0;
            return (
              <Card
                key={plan.code}
                className={`flex flex-col justify-between p-6 ${
                  plan.featured ? 'border-[#09A55A] ring-1 ring-[#09A55A]/25' : ''
                }`}
              >
                <div>
                  <div className="flex items-baseline justify-between gap-2">
                    <h3 className="font-display text-lg font-bold text-[#0B1F3A]">{plan.name}</h3>
                    <span className="font-mono text-xs text-[#526277] tabular-nums">
                      {plan.maxMembers.toLocaleString()} staff · {plan.maxSites} {plan.maxSites === 1 ? 'site' : 'sites'}
                    </span>
                  </div>
                  <p className="mt-1.5 text-xs leading-relaxed text-[#526277]">{plan.tagline}</p>

                  <div className="mt-5 border-y border-slate-100 py-4">
                    <div className="flex items-baseline gap-1.5">
                      <span className="font-display text-3xl font-extrabold tabular-nums text-[#0B1F3A]">
                        {price.formattedPerMonth}
                      </span>
                      <span className="text-xs text-[#526277]">/ month</span>
                    </div>
                    <p className="mt-1 font-mono text-[11px] tabular-nums text-[#526277]">
                      {isFree
                        ? 'No card required'
                        : billingCycle === 'ANNUAL'
                        ? `Billed ${price.formattedTotal} yearly · ${GATEWAY_DISPLAY_NAME[price.gateway]}`
                        : `Billed monthly · ${GATEWAY_DISPLAY_NAME[price.gateway]}`}
                    </p>
                  </div>

                  <ul className="mt-4 space-y-2.5 text-xs text-[#334155]">
                    {plan.highlights.map((item) => (
                      <li key={item} className="flex items-start gap-2">
                        <Check className="mt-0.5 h-3.5 w-3.5 shrink-0 text-[#09A55A]" />
                        <span>{item}</span>
                      </li>
                    ))}
                  </ul>
                </div>

                <div className="mt-6 pt-2">
                  <Link to={`/signup?plan=${plan.code}&cycle=${billingCycle}&currency=${currency}`} className="block">
                    <Button
                      variant={plan.featured ? 'primary' : 'outline'}
                      size="md"
                      className="w-full"
                    >
                      {plan.code === 'FREE' ? 'Start free workspace' : `Choose ${plan.name}`}
                    </Button>
                  </Link>
                </div>
              </Card>
            );
          })}
        </div>
      </section>

      {/* FAQ SECTION */}
      <section id="faq" className="border-t border-slate-200 bg-white py-16 lg:py-20">
        <div className="mx-auto max-w-4xl px-4 sm:px-6">
          <h2 className="font-display text-2xl font-bold tracking-tight text-[#0B1F3A] sm:text-3xl text-balance">
            Frequently asked questions
          </h2>
          <p className="mt-2 text-sm text-[#526277]">
            Operational, security, and billing details for IT and HR teams.
          </p>

          <div className="mt-8 divide-y divide-slate-200 border-y border-slate-200">
            {FAQ_ITEMS.map((item, idx) => {
              const isOpen = openFaqIndex === idx;
              return (
                <div key={item.q}>
                  <button
                    type="button"
                    aria-expanded={isOpen}
                    onClick={() => setOpenFaqIndex(isOpen ? -1 : idx)}
                    className="flex w-full items-center justify-between gap-4 py-4 text-left text-sm font-semibold text-[#0B1F3A] hover:text-[#09A55A] cursor-pointer"
                  >
                    <span>{item.q}</span>
                    <ChevronDown
                      className={`h-4 w-4 shrink-0 text-[#526277] transition-transform ${
                        isOpen ? 'rotate-180' : ''
                      }`}
                    />
                  </button>
                  {isOpen && (
                    <div className="pb-4 pr-8 text-sm leading-relaxed text-[#526277]">
                      {item.a}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      </section>

      {/* BOOK A DEMO / CONTACT */}
      <section id="demo" className="mx-auto max-w-6xl px-4 py-16 sm:px-6 lg:py-20">
        <div className="grid grid-cols-1 gap-10 lg:grid-cols-12">
          <div className="lg:col-span-5">
            <h2 className="font-display text-2xl font-bold tracking-tight text-[#0B1F3A] sm:text-3xl text-balance">
              Book a live architecture and rollout walkthrough.
            </h2>
            <p className="mt-3 text-sm leading-relaxed text-[#526277]">
              Planning a rollout across 10+ sites or need a dedicated Postgres cluster on Enterprise? Share your site count and our team will prepare a tailored test environment.
            </p>
            <dl className="mt-8 space-y-4 border-t border-slate-200 pt-6 text-sm">
              <div className="flex items-start gap-3">
                <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-[#09A55A]" />
                <div>
                  <dt className="font-semibold text-[#0B1F3A]">Security &amp; Compliance Review</dt>
                  <dd className="text-xs text-[#526277]">Inspect OpenAPI 3.1 contracts, DPA, and audit chain logs.</dd>
                </div>
              </div>
              <div className="flex items-start gap-3">
                <Clock className="mt-0.5 h-4 w-4 shrink-0 text-[#09A55A]" />
                <div>
                  <dt className="font-semibold text-[#0B1F3A]">15-Minute Site Geofence Setup</dt>
                  <dd className="text-xs text-[#526277]">Import staff rosters, configure shift rules, and test phone PIN clock-ins.</dd>
                </div>
              </div>
              <div className="flex items-start gap-3">
                <Lock className="mt-0.5 h-4 w-4 shrink-0 text-[#09A55A]" />
                <div>
                  <dt className="font-semibold text-[#0B1F3A]">Direct Engineering Support</dt>
                  <dd className="font-mono text-xs text-[#526277]">support@proofly.com</dd>
                </div>
              </div>
            </dl>
          </div>

          <div className="lg:col-span-7">
            <Card className="p-6 sm:p-8">
              {demoSubmittedRef ? (
                <div className="space-y-4">
                  <Alert
                    tone="success"
                    title="Walkthrough request recorded"
                    description={`Thank you. Reference ${demoSubmittedRef.refId} has been logged for ${demoSubmittedRef.companyName}. We will reply to ${demoSubmittedRef.workEmail} within one business day.`}
                  />
                  <div className="flex flex-wrap gap-3">
                    <Link to="/signup">
                      <Button variant="primary" size="md">Create free workspace now</Button>
                    </Link>
                    <Button variant="outline" size="md" onClick={() => setDemoSubmittedRef(null)}>
                      Submit another inquiry
                    </Button>
                  </div>
                </div>
              ) : (
                <form onSubmit={onDemoSubmit} className="space-y-4" noValidate>
                  <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                    <div>
                      <Label htmlFor="demo-fullname">Full name</Label>
                      <Input id="demo-fullname" placeholder="Tunde Bello" {...demoForm.register('fullName')} />
                      {demoForm.formState.errors.fullName && (
                        <p className="mt-1 text-xs text-red-700">{demoForm.formState.errors.fullName.message}</p>
                      )}
                    </div>
                    <div>
                      <Label htmlFor="demo-email">Work email</Label>
                      <Input
                        id="demo-email"
                        type="email"
                        placeholder="tunde@apapahaulage.ng"
                        {...demoForm.register('workEmail')}
                      />
                      {demoForm.formState.errors.workEmail && (
                        <p className="mt-1 text-xs text-red-700">{demoForm.formState.errors.workEmail.message}</p>
                      )}
                    </div>
                  </div>

                  <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                    <div className="sm:col-span-1">
                      <Label htmlFor="demo-company">Company</Label>
                      <Input id="demo-company" placeholder="Apapa Port Haulage" {...demoForm.register('companyName')} />
                      {demoForm.formState.errors.companyName && (
                        <p className="mt-1 text-xs text-red-700">{demoForm.formState.errors.companyName.message}</p>
                      )}
                    </div>
                    <div>
                      <Label htmlFor="demo-staff">Staff headcount</Label>
                      <select
                        id="demo-staff"
                        className="w-full min-h-[44px] rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-[#0B1F3A]"
                        {...demoForm.register('staffCount')}
                      >
                        <option value="1-20">1–20 staff</option>
                        <option value="21-75">21–75 staff</option>
                        <option value="76-250">76–250 staff</option>
                        <option value="251+">251+ staff</option>
                      </select>
                    </div>
                    <div>
                      <Label htmlFor="demo-sites">Active sites</Label>
                      <Input id="demo-sites" placeholder="e.g. 8" {...demoForm.register('siteCount')} />
                      {demoForm.formState.errors.siteCount && (
                        <p className="mt-1 text-xs text-red-700">{demoForm.formState.errors.siteCount.message}</p>
                      )}
                    </div>
                  </div>

                  <div>
                    <Label htmlFor="demo-notes">Rollout requirements (optional)</Label>
                    <textarea
                      id="demo-notes"
                      rows={3}
                      placeholder="Shift rules, payroll export cadence, or Paystack billing questions..."
                      className="w-full rounded-lg border border-slate-300 bg-white px-3.5 py-2.5 text-sm text-[#0B1F3A] placeholder:text-slate-400 focus:border-[#0B1F3A] focus:outline-none focus:ring-2 focus:ring-[#0B1F3A]/15"
                      {...demoForm.register('notes')}
                    />
                  </div>

                  <Button type="submit" variant="primary" size="lg" className="w-full">
                    Request Walkthrough
                  </Button>
                </form>
              )}
            </Card>
          </div>
        </div>
      </section>

      {/* DARK NAVY FOOTER */}
      <footer className="bg-[#0B1F3A] text-slate-300">
        <div className="mx-auto max-w-6xl px-4 py-12 sm:px-6">
          <div className="grid grid-cols-1 gap-8 border-b border-white/10 pb-10 md:grid-cols-12">
            <div className="md:col-span-5">
              <BrandMark light />
              <p className="mt-3 max-w-sm text-sm leading-relaxed text-slate-300">
                Multi-tenant geofenced staff attendance built with database-per-tenant PostgreSQL isolation, multi-currency Paystack &amp; Stripe ledger billing, and offline-first PWA capture.
              </p>
            </div>
            <div className="md:col-span-2">
              <h3 className="font-display text-xs font-bold uppercase tracking-wider text-white">Product</h3>
              <ul className="mt-3 space-y-2 text-xs">
                <li><a href="#features" className="hover:text-white">Features</a></li>
                <li><a href="#industries" className="hover:text-white">Industries</a></li>
                <li><a href="#pricing" className="hover:text-white">Pricing</a></li>
                <li><a href="#faq" className="hover:text-white">FAQ</a></li>
              </ul>
            </div>
            <div className="md:col-span-2">
              <h3 className="font-display text-xs font-bold uppercase tracking-wider text-white">Access</h3>
              <ul className="mt-3 space-y-2 text-xs">
                <li><Link to="/login" className="hover:text-white">Sign in</Link></li>
                <li><Link to="/signup" className="hover:text-white">Create workspace</Link></li>
                <li><Link to="/forgot-password" className="hover:text-white">Reset password</Link></li>
                <li><a href="#demo" className="hover:text-white">Book walkthrough</a></li>
              </ul>
            </div>
            <div className="md:col-span-3">
              <h3 className="font-display text-xs font-bold uppercase tracking-wider text-white">Legal &amp; Support</h3>
              <ul className="mt-3 space-y-2 text-xs">
                <li><Link to="/privacy" className="hover:text-white">Privacy Notice</Link></li>
                <li><Link to="/terms" className="hover:text-white">Terms of Service</Link></li>
                <li><Link to="/dpa" className="hover:text-white">Data Processing Addendum</Link></li>
                <li className="font-mono text-slate-400">support@proofly.com</li>
              </ul>
            </div>
          </div>

          <div className="mt-6 flex flex-wrap items-center justify-between gap-4 text-xs text-slate-400">
            <span>© {new Date().getFullYear()} Proofly. All rights reserved.</span>
            <span>OpenAPI 3.1 · RFC 7807 Problem Details</span>
          </div>
        </div>
      </footer>
    </div>
  );
};
