import test from 'node:test';
import assert from 'node:assert/strict';
import {
  PROOFLY_PLANS,
  computeAnnualFromMonthlyMinor,
  formatMoneyMinor,
  getDisplayPlanPrice,
  getPaymentGatewayForCurrency,
  haversineDistanceMeters,
  type SupportedCurrency,
} from './pricing';

test('catalog has exactly 4 active tiers and exact 10% annual discount across all currencies', () => {
  assert.equal(PROOFLY_PLANS.length, 4);
  assert.deepEqual(
    PROOFLY_PLANS.map((p) => p.code),
    ['FREE', 'STARTER', 'GROWTH', 'ENTERPRISE']
  );

  const currencies: SupportedCurrency[] = ['NGN', 'USD', 'GBP', 'KES', 'GHS'];
  for (const plan of PROOFLY_PLANS) {
    for (const cur of currencies) {
      const spec = plan.prices[cur];
      assert.equal(spec.annualMinor, computeAnnualFromMonthlyMinor(spec.monthlyMinor));
    }
  }
  // Starter NGN: ₦9,000/mo * 12 * 0.9 = ₦97,200/yr = 9_720_000 kobo
  assert.equal(computeAnnualFromMonthlyMinor(900_000), 9_720_000);
});

test('payment gateways route cleanly by currency (Paystack vs Stripe)', () => {
  assert.equal(getPaymentGatewayForCurrency('NGN'), 'PAYSTACK');
  assert.equal(getPaymentGatewayForCurrency('GHS'), 'PAYSTACK');
  assert.equal(getPaymentGatewayForCurrency('KES'), 'PAYSTACK');
  assert.equal(getPaymentGatewayForCurrency('USD'), 'STRIPE');
  assert.equal(getPaymentGatewayForCurrency('GBP'), 'STRIPE');
});

test('formatMoneyMinor and getDisplayPlanPrice render NGN and USD annual equivalents accurately', () => {
  assert.equal(formatMoneyMinor(900_000, 'NGN'), '₦9,000');
  assert.equal(formatMoneyMinor(0, 'NGN'), '₦0');

  const starter = PROOFLY_PLANS.find((p) => p.code === 'STARTER')!;
  const ngnAnnual = getDisplayPlanPrice(starter, 'ANNUAL', 'NGN');
  assert.equal(ngnAnnual.gateway, 'PAYSTACK');
  assert.equal(ngnAnnual.totalMinor, 9_720_000);
  assert.equal(ngnAnnual.formattedTotal, '₦97,200');
  assert.equal(ngnAnnual.formattedPerMonth, '₦8,100');

  const growth = PROOFLY_PLANS.find((p) => p.code === 'GROWTH')!;
  const usdAnnual = getDisplayPlanPrice(growth, 'ANNUAL', 'USD');
  assert.equal(usdAnnual.gateway, 'STRIPE');
  assert.equal(usdAnnual.totalMinor, 25_920);
  assert.equal(usdAnnual.formattedTotal, '$259.20');
  assert.equal(usdAnnual.formattedPerMonth, '$21.60');
});

test('haversineDistanceMeters computes zero for identical coordinates and accurate meter offsets', () => {
  assert.equal(haversineDistanceMeters(6.4541, 3.3947, 6.4541, 3.3947), 0);
  const meters = haversineDistanceMeters(6.4541, 3.3947, 6.4555, 3.3958);
  assert.ok(meters > 150 && meters < 240);
});
