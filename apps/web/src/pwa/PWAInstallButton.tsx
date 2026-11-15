import React, { useState } from 'react';
import { Download } from 'lucide-react';
import { Button } from '@proofly/ui';
import { usePWAInstall } from './usePWAInstall';

export const PWAInstallButton: React.FC = () => {
  const { isInstallable, isInstalled, isIOS, install } = usePWAInstall();
  const [showIOSGuide, setShowIOSGuide] = useState(false);

  if (isInstalled) {
    return null;
  }

  if (isInstallable) {
    return (
      <Button variant="secondary" size="sm" onClick={install}>
        <Download className="w-4 h-4" aria-hidden="true" />
        Install App
      </Button>
    );
  }

  if (isIOS) {
    return (
      <>
        <Button variant="outline" size="sm" onClick={() => setShowIOSGuide(true)}>
          Install on iOS
        </Button>
        {showIOSGuide && (
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="ios-install-title"
            className="fixed inset-0 z-50 flex items-center justify-center bg-[#0B1F3A]/50 p-4"
          >
            <div className="w-full max-w-sm rounded-xl border border-slate-200 bg-white p-6">
              <h2 id="ios-install-title" className="font-display text-lg font-bold text-[#0B1F3A]">
                Install Proofly on iOS
              </h2>
              <ol className="mt-3 space-y-2 text-sm text-slate-600 list-decimal list-inside">
                <li>Tap the Share button in the Safari toolbar.</li>
                <li>Select Add to Home Screen to install Proofly.</li>
              </ol>
              <Button className="mt-5 w-full" variant="secondary" onClick={() => setShowIOSGuide(false)}>
                Close
              </Button>
            </div>
          </div>
        )}
      </>
    );
  }

  return null;
};
