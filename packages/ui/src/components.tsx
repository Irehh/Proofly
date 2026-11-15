import React from 'react';
import { cn } from './cn';

export const BrandMark: React.FC<{ className?: string; light?: boolean }> = ({ className, light = false }) => (
  <span className={cn('inline-flex items-center gap-2.5 whitespace-nowrap shrink-0', className)}>
    <svg
      width="28"
      height="28"
      viewBox="0 0 32 32"
      fill="none"
      aria-hidden="true"
      className="shrink-0"
    >
      <rect width="32" height="32" rx="8" fill={light ? '#FFFFFF' : '#0B1F3A'} />
      <path
        d="M16 7C11.86 7 8.5 10.36 8.5 14.5C8.5 19.65 16 25.5 16 25.5C16 25.5 23.5 19.65 23.5 14.5C23.5 10.36 20.14 7 16 7Z"
        fill="#09A55A"
      />
      <path
        d="M13.5 14.6L15.2 16.3L18.8 12.7"
        stroke="#FFFFFF"
        strokeWidth="2.2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
    <span
      className={cn(
        'font-display text-lg font-bold tracking-tight',
        light ? 'text-white' : 'text-[#0B1F3A]'
      )}
    >
      Proofly
    </span>
  </span>
);

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'outline' | 'danger';
  size?: 'sm' | 'md' | 'lg';
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant = 'primary', size = 'md', type = 'button', ...props }, ref) => {
    const variants: Record<NonNullable<ButtonProps['variant']>, string> = {
      primary:
        'bg-[#09A55A] text-white hover:bg-[#078C4C] focus-visible:ring-[#09A55A] shadow-xs',
      secondary:
        'bg-[#0B1F3A] text-white hover:bg-[#132C4E] focus-visible:ring-[#0B1F3A]',
      outline:
        'border border-slate-300 bg-white text-[#0B1F3A] hover:bg-slate-50 focus-visible:ring-slate-400',
      danger:
        'bg-red-600 text-white hover:bg-red-700 focus-visible:ring-red-600',
    };
    const sizes: Record<NonNullable<ButtonProps['size']>, string> = {
      sm: 'min-h-[40px] px-3.5 py-2 text-xs',
      md: 'min-h-[44px] px-5 py-2.5 text-sm',
      lg: 'min-h-[48px] px-6 py-3 text-sm',
    };

    return (
      <button
        ref={ref}
        type={type}
        className={cn(
          'inline-flex items-center justify-center gap-2 rounded-lg font-medium whitespace-nowrap shrink-0 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 disabled:opacity-50 disabled:pointer-events-none cursor-pointer',
          variants[variant],
          sizes[size],
          className
        )}
        {...props}
      />
    );
  }
);
Button.displayName = 'Button';

export const Input = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  ({ className, ...props }, ref) => (
    <input
      ref={ref}
      className={cn(
        'w-full min-h-[44px] rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-sm text-[#0B1F3A] placeholder:text-slate-400 transition-colors focus:border-[#0B1F3A] focus:outline-none focus:ring-2 focus:ring-[#0B1F3A]/15 disabled:bg-slate-50 disabled:text-slate-500',
        className
      )}
      {...props}
    />
  )
);
Input.displayName = 'Input';

export const Label: React.FC<React.LabelHTMLAttributes<HTMLLabelElement>> = ({ className, ...props }) => (
  <label
    className={cn('block text-xs font-semibold text-[#0B1F3A] mb-1.5', className)}
    {...props}
  />
);

export const Card: React.FC<React.HTMLAttributes<HTMLDivElement>> = ({ className, ...props }) => (
  <div
    className={cn(
      'rounded-xl border border-slate-200 bg-white',
      className
    )}
    {...props}
  />
);

export const Alert: React.FC<{
  tone?: 'error' | 'info' | 'success';
  title: string;
  description?: string;
  meta?: string;
}> = ({ tone = 'error', title, description, meta }) => {
  const styles = {
    error: 'border-red-200 bg-red-50 text-red-900',
    info: 'border-slate-200 bg-slate-50 text-[#0B1F3A]',
    success: 'border-emerald-200 bg-emerald-50 text-emerald-950',
  };
  return (
    <div role="alert" className={cn('rounded-lg border p-3.5 text-sm', styles[tone])}>
      <p className="font-semibold">{title}</p>
      {description && <p className="mt-1 text-xs leading-relaxed opacity-90">{description}</p>}
      {meta && <p className="mt-1.5 font-mono text-[11px] opacity-75">{meta}</p>}
    </div>
  );
};

export * from './pricing';
