import { forwardRef } from 'react';

export function cx(...values) {
  return values.filter(Boolean).join(' ');
}

export function AppShell({ sidebar, children, className = '' }) {
  return (
    <div className={cx('ab-shell', sidebar && 'ab-shell--with-sidebar', className)}>
      {sidebar ? <aside className="ab-sidebar">{sidebar}</aside> : null}
      <main className="ab-main">{children}</main>
    </div>
  );
}

export function PageHeader({ eyebrow, title, description, actions }) {
  return (
    <header className="ab-page-header">
      <div>
        {eyebrow ? <div className="ab-eyebrow">{eyebrow}</div> : null}
        <h1>{title}</h1>
        {description ? <p>{description}</p> : null}
      </div>
      {actions ? <div className="ab-actions">{actions}</div> : null}
    </header>
  );
}

export function Card({ as: Element = 'section', className = '', children, ...props }) {
  return <Element className={cx('ab-card', className)} {...props}>{children}</Element>;
}

export function CardHeader({ title, description, actions }) {
  return (
    <div className="ab-card-header">
      <div><h2>{title}</h2>{description ? <p>{description}</p> : null}</div>
      {actions ? <div className="ab-actions">{actions}</div> : null}
    </div>
  );
}

export function Button({ variant = 'primary', size = 'md', busy = false, disabled = false, children, className = '', ...props }) {
  return (
    <button className={cx('ab-button', `ab-button--${variant}`, `ab-button--${size}`, className)} {...props} disabled={busy || disabled}>
      {busy ? <span className="ab-spinner" aria-hidden="true" /> : null}
      <span>{children}</span>
    </button>
  );
}

export function IconButton({ label, children, className = '', ...props }) {
  return <button className={cx('ab-icon-button', className)} aria-label={label} title={label} {...props}>{children}</button>;
}

export function Badge({ tone = 'neutral', children }) {
  return <span className={cx('ab-badge', `ab-badge--${tone}`)}>{children}</span>;
}

export function Field({ label, hint, error, children }) {
  return (
    <label className="ab-field">
      <span className="ab-field__label">{label}</span>
      {children}
      {error ? <span className="ab-field__error">{error}</span> : hint ? <span className="ab-field__hint">{hint}</span> : null}
    </label>
  );
}

export const TextInput = forwardRef(function TextInput({ className = '', ...props }, ref) {
  return <input ref={ref} className={cx('ab-input', className)} {...props} />;
});

export const TextArea = forwardRef(function TextArea({ className = '', ...props }, ref) {
  return <textarea ref={ref} className={cx('ab-input', 'ab-textarea', className)} {...props} />;
});

export function StatusNotice({ tone = 'info', title, children, onDismiss }) {
  return (
    <div className={cx('ab-notice', `ab-notice--${tone}`)} role={tone === 'danger' ? 'alert' : 'status'}>
      <div><strong>{title}</strong>{children ? <p>{children}</p> : null}</div>
      {onDismiss ? <IconButton label="Dismiss" onClick={onDismiss}>×</IconButton> : null}
    </div>
  );
}

export function EmptyState({ icon = '◇', title, description, action }) {
  return (
    <div className="ab-empty">
      <div className="ab-empty__icon" aria-hidden="true">{icon}</div>
      <h3>{title}</h3><p>{description}</p>{action ? <div>{action}</div> : null}
    </div>
  );
}

export function Skeleton({ lines = 3 }) {
  return <div className="ab-skeleton" aria-label="Loading">{Array.from({ length: lines }, (_, index) => <span key={index} />)}</div>;
}

export function Avatar({ name = '?', src, size = 'md' }) {
  const initials = name.split(/\s+/).map(part => part[0]).join('').slice(0, 2).toUpperCase();
  return src
    ? <img className={cx('ab-avatar', `ab-avatar--${size}`)} src={src} alt={name} />
    : <span className={cx('ab-avatar', `ab-avatar--${size}`)} aria-label={name}>{initials}</span>;
}

export function Modal({ open, title, onClose, children, footer }) {
  if (!open) return null;
  return (
    <div className="ab-modal-backdrop" role="presentation" onMouseDown={event => event.target === event.currentTarget && onClose?.()}>
      <section className="ab-modal" role="dialog" aria-modal="true" aria-label={title}>
        <div className="ab-modal__header"><h2>{title}</h2><IconButton label="Close" onClick={onClose}>×</IconButton></div>
        <div className="ab-modal__body">{children}</div>
        {footer ? <div className="ab-modal__footer">{footer}</div> : null}
      </section>
    </div>
  );
}
