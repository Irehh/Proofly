import React from 'react';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { BrandMark, Button } from '@proofly/ui';
import { LandingPage } from './LandingPage';
import { LegalPage } from './LegalPages';

export const MarketingApp: React.FC = () => (
  <BrowserRouter>
    <div className="min-h-screen bg-[#F7F9FC] text-[#0B1F3A]">
      <header className="sticky top-0 z-30 border-b border-slate-200 bg-white/95 backdrop-blur-xs">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between gap-8 px-4 sm:px-6">
          <a href="/" className="focus-visible:outline-none">
            <BrandMark />
          </a>
          <nav aria-label="Marketing navigation" className="hidden md:flex items-center gap-6 text-sm font-medium text-[#526277]">
            <a href="#features" className="whitespace-nowrap shrink-0 hover:text-[#0B1F3A]">Features</a>
            <a href="#industries" className="whitespace-nowrap shrink-0 hover:text-[#0B1F3A]">Industries</a>
            <a href="#pricing" className="whitespace-nowrap shrink-0 hover:text-[#0B1F3A]">Pricing</a>
            <a href="#faq" className="whitespace-nowrap shrink-0 hover:text-[#0B1F3A]">FAQ</a>
            <a href="/login" className="whitespace-nowrap shrink-0 hover:text-[#0B1F3A]">Sign in</a>
          </nav>
          <a href="/signup" className="shrink-0">
            <Button variant="primary" size="sm">Get started</Button>
          </a>
        </div>
      </header>
      <Routes>
        <Route path="/" element={<LandingPage />} />
        <Route path="/privacy" element={<LegalPage doc="privacy" />} />
        <Route path="/terms" element={<LegalPage doc="terms" />} />
        <Route path="/dpa" element={<LegalPage doc="dpa" />} />
        <Route path="*" element={<LandingPage />} />
      </Routes>
    </div>
  </BrowserRouter>
);

export default MarketingApp;
