import test from 'node:test';
import assert from 'node:assert/strict';
import { createProoflyClient, generateCorrelationId, normalizeProblemDetail } from './index';

test('generateCorrelationId returns non-empty unique identifiers', () => {
  const a = generateCorrelationId();
  const b = generateCorrelationId();
  assert.ok(a.length > 8);
  assert.notEqual(a, b);
});

test('normalizeProblemDetail preserves RFC 7807 ProblemDetail payloads', () => {
  const problem = {
    type: 'https://api.proofly.com/errors/prooflY_pin_locked',
    title: 'PIN LOCKED',
    status: 423,
    detail: 'PIN locked',
    errorCode: 'PROOFLY_PIN_LOCKED',
    correlationId: 'corr-123',
    timestamp: '2026-11-15T00:00:00Z',
  };
  const out = normalizeProblemDetail(problem);
  assert.equal(out.errorCode, 'PROOFLY_PIN_LOCKED');
  assert.equal(out.status, 423);
  assert.equal(out.correlationId, 'corr-123');
});

test('normalizeProblemDetail wraps transport errors cleanly', () => {
  const out = normalizeProblemDetail(new Error('Failed to fetch'));
  assert.equal(out.errorCode, 'PROOFLY_NETWORK_UNAVAILABLE');
  assert.equal(out.status, 503);
  assert.equal(out.detail, 'Failed to fetch');
});

test('createProoflyClient creates typed client instance', () => {
  const client = createProoflyClient({
    baseUrl: 'http://localhost:8080',
    getAccessToken: () => 'jwt-token',
  });
  assert.equal(typeof client.GET, 'function');
  assert.equal(typeof client.POST, 'function');
  assert.equal(typeof client.DELETE, 'function');
});
