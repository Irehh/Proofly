import React from 'react';
import { BrowserRouter, Link, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrandMark, Button } from '@proofly/ui';
import { AuthProvider, useAuth } from './lib/authStore';
import { LoginPage } from './pages/LoginPage';
import { SignupOrgPage } from './pages/SignupOrgPage';
import { WorkspacePage } from './pages/WorkspacePage';
import { PWAInstallButton } from './pwa/PWAInstallButton';
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
        <Link to={user ? '/workspace' : '/login'} className="focus-visible:outline-none">
          <BrandMark />
        </Link>

        <nav aria-label="Primary navigation" className="hidden md:flex items-center gap-6 text-sm font-medium text-[#526277]">
          <Link to="/login" className="whitespace-nowrap shrink-0 hover:text-[#0B1F3A] hover:underline underline-offset-4">
            Sign in
          </Link>
          <Link to="/signup" className="whitespace-nowrap shrink-0 hover:text-[#0B1F3A] hover:underline underline-offset-4">
            Create org
          </Link>
          {user && (
            <Link to="/workspace" className="whitespace-nowrap shrink-0 hover:text-[#0B1F3A] hover:underline underline-offset-4">
              Workspace
            </Link>
          )}
        </nav>

        <div className="flex items-center gap-3 shrink-0">
          <PWAInstallButton />
          {!user ? (
            <Link to={onSignup ? '/login' : '/signup'}>
              <Button variant={onSignup ? 'outline' : 'primary'} size="sm">
                {onSignup ? 'Sign in' : 'Get started'}
              </Button>
            </Link>
          ) : (
            <Link to="/workspace">
              <Button variant="secondary" size="sm">
                Workspace
              </Button>
            </Link>
          )}
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

  return (
    <div className="min-h-screen flex flex-col bg-[#F7F9FC]">
      <TopBar />
      <main className="flex-1">
        <Routes>
          <Route path="/login" element={user ? <Navigate to="/workspace" replace /> : <LoginPage />} />
          <Route path="/signup" element={user ? <Navigate to="/workspace" replace /> : <SignupOrgPage />} />
          <Route
            path="/workspace"
            element={
              <ProtectedRoute>
                <WorkspacePage />
              </ProtectedRoute>
            }
          />
          <Route path="*" element={<Navigate to={user ? '/workspace' : '/login'} replace />} />
        </Routes>
      </main>
      <footer className="border-t border-slate-200 bg-white py-6">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4 px-4 text-xs text-[#526277] sm:px-6">
          <span>Proofly · Geofenced Staff Attendance</span>
          <span>OpenAPI 3.1 Contract-First · RFC 7807</span>
        </div>
      </footer>
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
