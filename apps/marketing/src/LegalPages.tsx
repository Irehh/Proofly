import React from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@proofly/ui';

interface LegalDocProps {
  doc: 'privacy' | 'terms' | 'dpa';
}

export const LegalPage: React.FC<LegalDocProps> = ({ doc }) => {
  const titles: Record<LegalDocProps['doc'], { title: string; updated: string; summary: string }> = {
    privacy: {
      title: 'Privacy Notice',
      updated: 'Effective November 2026',
      summary: 'How Proofly handles staff location coordinates, device identifiers, and PII encryption across isolated tenant databases.',
    },
    terms: {
      title: 'Terms of Service',
      updated: 'Effective November 2026',
      summary: 'Commercial subscription terms, Paystack billing cycles, grace periods, and acceptable platform usage for organizations.',
    },
    dpa: {
      title: 'Data Processing Addendum (DPA)',
      updated: 'Effective November 2026',
      summary: 'Controller-processor commitments under NDPA and GDPR principles, database-per-tenant isolation guarantees, and subprocessor controls.',
    },
  };

  const current = titles[doc];

  return (
    <div className="mx-auto max-w-4xl px-4 py-12 sm:px-6 lg:py-16">
      <div className="border-b border-slate-200 pb-6">
        <div className="flex flex-wrap items-center gap-3 text-xs text-[#526277]">
          <Link to="/" className="font-semibold text-[#0B1F3A] hover:underline">Proofly Home</Link>
          <span aria-hidden="true">/</span>
          <span>{current.title}</span>
          <span aria-hidden="true">·</span>
          <span>{current.updated}</span>
        </div>
        <h1 className="mt-3 font-display text-3xl font-bold tracking-tight text-[#0B1F3A]">
          {current.title}
        </h1>
        <p className="mt-2 text-base text-[#526277]">{current.summary}</p>
      </div>

      {doc === 'privacy' && (
        <div className="mt-8 space-y-6 text-sm leading-relaxed text-[#334155]">
          <section>
            <h2 className="font-display text-lg font-bold text-[#0B1F3A]">01. Location Data Minimization</h2>
            <p className="mt-2">
              Proofly captures GPS coordinates strictly at the moment a staff member initiates a clock-in or clock-out event. Background continuous location tracking is disabled. Coordinates are evaluated server-side against the assigned site&apos;s PostGIS geofence polygon/radius and stored in the employer&apos;s isolated tenant database.
            </p>
          </section>
          <section>
            <h2 className="font-display text-lg font-bold text-[#0B1F3A]">02. PII Encryption &amp; Auditability</h2>
            <p className="mt-2">
              Sensitive credentials (Argon2id password and PIN hashes), TOTP MFA secrets (AES-256-GCM encrypted), and refresh token families (SHA-256 hashed) are never logged in plaintext. Notification outbox tables mask email addresses and phone numbers on administrative read views.
            </p>
          </section>
          <section>
            <h2 className="font-display text-lg font-bold text-[#0B1F3A]">03. Tenant Isolation &amp; Deletion</h2>
            <p className="mt-2">
              Each organization operates on its own PostgreSQL database. When an organization offboards or requests erasure, Proofly revokes all active refresh token families, marks the tenant directory record for deletion, and drops the tenant database after contractual retention windows expire.
            </p>
          </section>
        </div>
      )}

      {doc === 'terms' && (
        <div className="mt-8 space-y-6 text-sm leading-relaxed text-[#334155]">
          <section>
            <h2 className="font-display text-lg font-bold text-[#0B1F3A]">01. Subscriptions &amp; Plan Limits</h2>
            <p className="mt-2">
              Workspaces start on the Free plan (up to 4 staff members and 1 geofenced site). Paid plans (Starter, Team, Growth, Business, Enterprise) are billed monthly or annually (10% discount on annual commitments) in NGN via Paystack, with multi-currency display estimates for planning convenience.
            </p>
          </section>
          <section>
            <h2 className="font-display text-lg font-bold text-[#0B1F3A]">02. Grace Periods &amp; Dunning</h2>
            <p className="mt-2">
              If a scheduled renewal fails, Proofly transitions the subscription to a configurable grace period (default 5 days) and sends automated email notifications so administrators can settle the invoice without losing recorded attendance logs.
            </p>
          </section>
          <section>
            <h2 className="font-display text-lg font-bold text-[#0B1F3A]">03. Attendance Corrections &amp; Exports</h2>
            <p className="mt-2">
              Employers retain full ownership of all attendance logs, corrections, leave approvals, and payroll CSV exports generated inside their workspace.
            </p>
          </section>
        </div>
      )}

      {doc === 'dpa' && (
        <div className="mt-8 space-y-6 text-sm leading-relaxed text-[#334155]">
          <section>
            <h2 className="font-display text-lg font-bold text-[#0B1F3A]">01. Roles &amp; Isolation Boundary</h2>
            <p className="mt-2">
              The Customer organization acts as Data Controller for employee attendance, shift rules, and leave records. Proofly acts as Data Processor, enforcing strict database-per-tenant routing derived exclusively from signed RS256 JWT tenant claims (`tid`) rather than client-supplied headers.
            </p>
          </section>
          <section>
            <h2 className="font-display text-lg font-bold text-[#0B1F3A]">02. Tamper-Evident Audit Chain</h2>
            <p className="mt-2">
              Every administrative action, password reset, device revocation, and subscription ledger posting is written to append-only PostgreSQL audit tables protected by `BEFORE UPDATE OR DELETE` database triggers and SHA-256 hash chaining.
            </p>
          </section>
          <section>
            <h2 className="font-display text-lg font-bold text-[#0B1F3A]">03. Subprocessors &amp; Contact</h2>
            <p className="mt-2">
              Payment settlement is processed through Paystack, and transactional email/SMS alerts use the configured SMTP/SMS gateways. Security and DPA inquiries can be directed to <span className="font-mono text-[#0B1F3A]">support@proofly.com</span>.
            </p>
          </section>
        </div>
      )}

      <div className="mt-10 flex flex-wrap items-center gap-4 border-t border-slate-200 pt-6">
        <Link to="/signup">
          <Button variant="primary" size="md">Start free workspace</Button>
        </Link>
        <Link to="/" className="text-sm font-medium text-[#0B1F3A] hover:underline">
          Back to Proofly overview
        </Link>
      </div>
    </div>
  );
};
