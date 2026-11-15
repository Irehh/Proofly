export type SupportedCurrency = 'NGN' | 'USD' | 'GBP' | 'KES' | 'GHS';
export type BillingCycle = 'MONTHLY' | 'ANNUAL';
export type PaymentGatewayCode = 'PAYSTACK' | 'STRIPE';
export type PlanCode = 'FREE' | 'STARTER' | 'GROWTH' | 'ENTERPRISE';
export type PaidPlanCode = Exclude<PlanCode, 'FREE'>;

export interface CurrencyPriceSpec {
  monthlyMinor: number;
  annualMinor: number;
  gateway: PaymentGatewayCode;
}

export interface CatalogPlan {
  code: PlanCode;
  name: string;
  tagline: string;
  maxMembers: number;
  maxSites: number;
  prices: Record<SupportedCurrency, CurrencyPriceSpec>;
  highlights: string[];
  featured?: boolean;
}

export const CURRENCY_GATEWAY_MAP: Record<SupportedCurrency, PaymentGatewayCode> = {
  NGN: 'PAYSTACK',
  GHS: 'PAYSTACK',
  KES: 'PAYSTACK',
  USD: 'STRIPE',
  GBP: 'STRIPE',
};

export const GATEWAY_DISPLAY_NAME: Record<PaymentGatewayCode, string> = {
  PAYSTACK: 'Paystack',
  STRIPE: 'Stripe',
};

/**
 * Mirrors V3__multi_currency_gateways_and_4_plans.sql seeded 4-tier catalog
 * (money stored as BIGINT minor units; annual applies exact 10% discount).
 */
export const PROOFLY_PLANS: readonly CatalogPlan[] = [
  {
    code: 'FREE',
    name: 'Free',
    tagline: 'For small single-site teams validating geofenced clock-ins.',
    maxMembers: 4,
    maxSites: 1,
    prices: {
      NGN: { monthlyMinor: 0, annualMinor: 0, gateway: 'PAYSTACK' },
      USD: { monthlyMinor: 0, annualMinor: 0, gateway: 'STRIPE' },
      GBP: { monthlyMinor: 0, annualMinor: 0, gateway: 'STRIPE' },
      KES: { monthlyMinor: 0, annualMinor: 0, gateway: 'PAYSTACK' },
      GHS: { monthlyMinor: 0, annualMinor: 0, gateway: 'PAYSTACK' },
    },
    highlights: [
      'Up to 4 active staff members',
      '1 geofenced site with timezone',
      'Out-of-fence review queue',
      'Installable mobile & desktop PWA',
    ],
  },
  {
    code: 'STARTER',
    name: 'Starter',
    tagline: 'For growing retail stores, clinics, and field crews.',
    maxMembers: 25,
    maxSites: 3,
    prices: {
      NGN: { monthlyMinor: 900_000, annualMinor: 9_720_000, gateway: 'PAYSTACK' },
      USD: { monthlyMinor: 800, annualMinor: 8_640, gateway: 'STRIPE' },
      GBP: { monthlyMinor: 600, annualMinor: 6_480, gateway: 'STRIPE' },
      KES: { monthlyMinor: 90_000, annualMinor: 972_000, gateway: 'PAYSTACK' },
      GHS: { monthlyMinor: 11_000, annualMinor: 118_800, gateway: 'PAYSTACK' },
    },
    highlights: [
      'Up to 25 staff members & 3 sites',
      'Payroll-ready CSV hour exports',
      'Late & overtime threshold rules',
      'Email + phone PIN onboarding',
    ],
  },
  {
    code: 'GROWTH',
    name: 'Growth',
    tagline: 'Multi-branch operations with manager approvals & TOTP MFA.',
    maxMembers: 100,
    maxSites: 15,
    featured: true,
    prices: {
      NGN: { monthlyMinor: 2_900_000, annualMinor: 31_320_000, gateway: 'PAYSTACK' },
      USD: { monthlyMinor: 2_400, annualMinor: 25_920, gateway: 'STRIPE' },
      GBP: { monthlyMinor: 1_900, annualMinor: 20_520, gateway: 'STRIPE' },
      KES: { monthlyMinor: 290_000, annualMinor: 3_132_000, gateway: 'PAYSTACK' },
      GHS: { monthlyMinor: 35_000, annualMinor: 378_000, gateway: 'PAYSTACK' },
    },
    highlights: [
      'Up to 100 staff members & 15 sites',
      'Manager leave routing & corrections',
      'Admin TOTP two-factor enforcement',
      'Immutable audit chain & SMS alerts',
    ],
  },
  {
    code: 'ENTERPRISE',
    name: 'Enterprise',
    tagline: 'High-scale operations with cluster isolation & custom SLAs.',
    maxMembers: 5000,
    maxSites: 500,
    prices: {
      NGN: { monthlyMinor: 9_500_000, annualMinor: 102_600_000, gateway: 'PAYSTACK' },
      USD: { monthlyMinor: 7_900, annualMinor: 85_320, gateway: 'STRIPE' },
      GBP: { monthlyMinor: 6_200, annualMinor: 66_960, gateway: 'STRIPE' },
      KES: { monthlyMinor: 950_000, annualMinor: 10_260_000, gateway: 'PAYSTACK' },
      GHS: { monthlyMinor: 115_000, annualMinor: 1_242_000, gateway: 'PAYSTACK' },
    },
    highlights: [
      'Up to 5,000 staff & 500 sites',
      'Dedicated tenant pool / cluster routing',
      'Priority outbox delivery & replay',
      'Custom DPA & onboarding architect',
    ],
  },
];

const CURRENCY_META: Record<SupportedCurrency, { symbol: string; locale: string }> = {
  NGN: { symbol: '₦', locale: 'en-NG' },
  USD: { symbol: '$', locale: 'en-US' },
  GBP: { symbol: '£', locale: 'en-GB' },
  KES: { symbol: 'KSh ', locale: 'en-KE' },
  GHS: { symbol: 'GH₵', locale: 'en-GH' },
};

export function getPaymentGatewayForCurrency(currency: SupportedCurrency): PaymentGatewayCode {
  return CURRENCY_GATEWAY_MAP[currency];
}

export function computeAnnualFromMonthlyMinor(monthlyMinor: number): number {
  return Math.round((monthlyMinor * 12 * 90) / 100);
}

export function formatMoneyMinor(minorUnits: number, currency: SupportedCurrency = 'NGN'): string {
  const major = minorUnits / 100;
  const meta = CURRENCY_META[currency];
  const hasCents = minorUnits % 100 !== 0;
  const formatted = new Intl.NumberFormat(meta.locale, {
    minimumFractionDigits: hasCents ? 2 : 0,
    maximumFractionDigits: hasCents ? 2 : 0,
  }).format(major);
  return `${meta.symbol}${formatted}`;
}

export function getDisplayPlanPrice(
  plan: Pick<CatalogPlan, 'prices'>,
  cycle: BillingCycle,
  currency: SupportedCurrency
): {
  gateway: PaymentGatewayCode;
  totalMinor: number;
  perMonthMinor: number;
  formattedPerMonth: string;
  formattedTotal: string;
} {
  const spec = plan.prices[currency];
  const gateway = spec.gateway;
  const totalMinor = cycle === 'ANNUAL' ? spec.annualMinor : spec.monthlyMinor;
  if (totalMinor === 0) {
    return {
      gateway,
      totalMinor: 0,
      perMonthMinor: 0,
      formattedPerMonth: formatMoneyMinor(0, currency),
      formattedTotal: formatMoneyMinor(0, currency),
    };
  }
  const perMonthMinor = cycle === 'ANNUAL' ? Math.round(totalMinor / 12) : totalMinor;
  return {
    gateway,
    totalMinor,
    perMonthMinor,
    formattedPerMonth: formatMoneyMinor(perMonthMinor, currency),
    formattedTotal: formatMoneyMinor(totalMinor, currency),
  };
}

/**
 * Pure WGS84 Haversine distance in meters for the landing page's interactive geofence check-in simulator.
 */
export function haversineDistanceMeters(
  lat1: number,
  lon1: number,
  lat2: number,
  lon2: number
): number {
  const R = 6_371_000;
  const toRad = (deg: number) => (deg * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return Math.round(R * c);
}
