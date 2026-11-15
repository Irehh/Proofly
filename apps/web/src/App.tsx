import React from 'react';
import { BrowserRouter, Link, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrandMark, Button } from '@proofly/ui';
import { LandingPage } from '../../marketing/src/LandingPage';
import { LegalPage } from '../../marketing/src/LegalPages';
import { AuthProvider, useAuth } from './lib/authStore';
import { LoginPage } from './pages/LoginPage';
import { SignupOrgPage } from './pages/SignupOrgPage';
import { ForgotPasswordPage } from './pages/ForgotPasswordPage';
import { ResetPasswordPage } from './pages/ResetPasswordPage';
import { VerifyEmailPage } from './pages/VerifyEmailPage';
import { BillingCallbackPage } from './pages/BillingCallbackPage';
import { AcceptInvitePage } from './pages/AcceptInvitePage';
import { WorkspacePage } from './pages/WorkspacePage';
import { OfflineIndicator } from './pwa/OfflineIndicator';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false, staleTime: 30_000 },
  },
});

const TopBar: React.FC = () => {
  const { user } = useAuth();
  const { pathname } = useLocation();
  const onSignup = pathname === '/signup';

  return (
    <header className="sticky top-0 z-30 border-b border-slate-200 bg-white/95 backdrop-blur-xs">
      <div className="mx-auto flex h-16 max-w-6xl items-center justify-between gap-8 px-4 sm:px-6">
        <Link to="/" className="focus-visible:outline-none">
          <BrandMark />
        </Link>

        <nav aria-label="Primary navigation" className="hidden md:flex items-center gap-6 text-sm font-medium text-[#526277]">
          <a href="/#features" className="whitespace-nowrap shrink-0 hover:text-[#0B1F3A] hover:underline underline-offset-4">
            Features
          </a>
          <a href="/#industries" className="whitespace-nowrap shrink-0 hover:text-[#0B1F3A] hover:underline underline-offset-4">
            Industries
          </a>
          <a href="/#pricing" className="whitespace-nowrap shrink-0 hover:text-[#0B1F3A] hover:underline underline-offset-4">
            Pricing
          </a>
          <a href="/#faq" className="whitespace-nowrap shrink-0 hover:text-[#0B1F3A] hover:underline underline-offset-4">
            FAQ
          </a>
          {!user && (
            <Link to="/login" className="whitespace-nowrap shrink-0 hover:text-[#0B1F3A] hover:underline underline-offset-4">
              Sign in
            </Link>
          )}
        </nav>

        <div className="flex items-center shrink-0">
          {!user ? (
            <Link to={onSignup ? '/login' : '/signup'}>
              <Button variant={onSignup ? 'outline' : 'primary'} size="sm">
                {onSignup ? 'Sign in' : 'Get started'}
              </Button>
            </Link>
          ) : !pathname.startsWith('/workspace') ? (
            <Link to="/workspace">
              <Button variant="secondary" size="sm">
                Workspace
              </Button>
            </Link>
          ) : null}
        </div>
      </div>
    </header>
  );
};

const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, isRestoring } = useAuth();
  if (isRestoring) {
    return (
      <div className="mx-auto max-w-6xl px-4 py-16 text-sm text-[#526277]">
        Restoring session...
      </div>
    );
  }
  if (!user) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
};

const AppRoutes: React.FC = () => {
  const { user } = useAuth();
  const { pathname } = useLocation();
  const isLanding = pathname === '/';

  return (
    <div className="min-h-screen flex flex-col bg-[#F7F9FC]">
      <TopBar />
      <main className="flex-1">
        <Routes>
          <Route path="/" element={<LandingPage />} />
          <Route path="/privacy" element={<LegalPage doc="privacy" />} />
          <Route path="/terms" element={<LegalPage doc="terms" />} />
          <Route path="/dpa" element={<LegalPage doc="dpa" />} />
          <Route path="/login" element={user ? <Navigate to="/workspace" replace /> : <LoginPage />} />
          <Route path="/signup" element={user ? <Navigate to="/workspace" replace /> : <SignupOrgPage />} />
          <Route path="/forgot-password" element={<ForgotPasswordPage />} />
          <Route path="/reset-password" element={<ResetPasswordPage />} />
          <Route path="/verify-email" element={<VerifyEmailPage />} />
          <Route path="/accept-invite" element={<AcceptInvitePage />} />
          <Route path="/billing/callback" element={<BillingCallbackPage />} />
          <Route
            path="/workspace"
            element={
              <ProtectedRoute>
                <WorkspacePage />
              </ProtectedRoute>
            }
          />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
      {!isLanding && (
        <footer className="border-t border-slate-200 bg-white py-6">
          <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4 px-4 text-xs text-[#526277] sm:px-6">
            <span>Proofly · Geofenced Staff Attendance</span>
            <div className="flex items-center gap-4">
              <Link to="/privacy" className="hover:text-[#0B1F3A]">Privacy</Link>
              <Link to="/terms" className="hover:text-[#0B1F3A]">Terms</Link>
              <Link to="/dpa" className="hover:text-[#0B1F3A]">DPA</Link>
            </div>
          </div>
        </footer>
      )}
      <OfflineIndicator />
    </div>
  );
};

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <AppRoutes />
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
