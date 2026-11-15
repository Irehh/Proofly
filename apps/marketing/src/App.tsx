import React from 'react';
import { BrandMark, Button } from '@proofly/ui';

/**
 * P0 scaffold for the statically prerendered Proofly marketing site (expanded in P4 with vite-react-ssg).
 */
export const MarketingShell: React.FC = () => (
  <div className="min-h-screen bg-white text-[#0B1F3A]">
    <header className="border-b border-slate-200 px-6 py-4">
      <div className="mx-auto flex max-w-6xl items-center justify-between gap-8">
        <BrandMark />
        <nav className="hidden md:flex items-center gap-6 text-sm font-medium text-[#526277]">
          <a href="#features" className="whitespace-nowrap hover:text-[#0B1F3A]">Features</a>
          <a href="#industries" className="whitespace-nowrap hover:text-[#0B1F3A]">Industries</a>
          <a href="#pricing" className="whitespace-nowrap hover:text-[#0B1F3A]">Pricing</a>
          <a href="#faq" className="whitespace-nowrap hover:text-[#0B1F3A]">FAQ</a>
        </nav>
        <Button variant="primary" size="sm">Book a demo</Button>
      </div>
    </header>
  </div>
);

export default MarketingShell;
