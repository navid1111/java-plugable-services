"""Dependency-free React primitives copied into every generated workspace.

The kit gives the coding agent reliable building blocks without constraining the
visual design or adding packages to each app. Files are only seeded when missing,
so an app can customize or replace them after generation.
"""

UI_COMPONENTS = r'''import { forwardRef } from 'react';

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
'''

PATTERN_COMPONENTS = r'''import { Button, Card, EmptyState, Field, Skeleton, StatusNotice, TextArea, TextInput } from './AppBuilderUI.jsx';

export function AsyncBoundary({ loading, error, empty, emptyTitle = 'Nothing here yet', emptyDescription = 'New items will appear here.', children }) {
  if (loading) return <Card><Skeleton lines={4} /></Card>;
  if (error) return <StatusNotice tone="danger" title="Could not load this section">{String(error.message || error)}</StatusNotice>;
  if (empty) return <Card><EmptyState title={emptyTitle} description={emptyDescription} /></Card>;
  return children;
}

export function AuthForm({ mode = 'login', busy, error, onSubmit }) {
  async function submit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const values = Object.fromEntries(new FormData(form));
    await onSubmit?.(values, form);
  }
  return (
    <Card as="form" className="ab-stack" onSubmit={submit}>
      <div><h2>{mode === 'register' ? 'Create account' : 'Welcome back'}</h2><p>Use your account to continue.</p></div>
      <Field label="Username"><TextInput name="username" autoComplete="username" required /></Field>
      <Field label="Password" error={error}><TextInput name="password" type="password" autoComplete={mode === 'register' ? 'new-password' : 'current-password'} required /></Field>
      <Button type="submit" busy={busy}>{mode === 'register' ? 'Create account' : 'Sign in'}</Button>
    </Card>
  );
}

export function SearchBox({ value, onChange, onSubmit, placeholder = 'Search…', busy = false }) {
  function submit(event) { event.preventDefault(); onSubmit?.(value); }
  return (
    <form className="ab-search" role="search" onSubmit={submit}>
      <TextInput value={value} onChange={event => onChange?.(event.target.value)} placeholder={placeholder} aria-label={placeholder} />
      <Button type="submit" variant="secondary" busy={busy}>Search</Button>
    </form>
  );
}

export function Composer({ title = 'Create something', label = 'Content', maxLength = 280, busy, onSubmit }) {
  async function submit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const values = Object.fromEntries(new FormData(form));
    await onSubmit?.(values, form);
  }
  return (
    <Card as="form" className="ab-stack" onSubmit={submit}>
      <h2>{title}</h2>
      <Field label={label} hint={`Maximum ${maxLength} characters`}><TextArea name="content" maxLength={maxLength} required /></Field>
      <div className="ab-actions"><Button type="submit" busy={busy}>Publish</Button></div>
    </Card>
  );
}

export function CollectionGrid({ items = [], renderItem, emptyTitle = 'No results', emptyDescription = 'Try changing your filters.' }) {
  if (!items.length) return <EmptyState title={emptyTitle} description={emptyDescription} />;
  return <div className="ab-grid">{items.map((item, index) => renderItem(item, index))}</div>;
}

export function Pagination({ page = 1, hasPrevious, hasNext, onChange }) {
  return (
    <nav className="ab-pagination" aria-label="Pagination">
      <Button variant="secondary" disabled={!hasPrevious} onClick={() => onChange?.(page - 1)}>Previous</Button>
      <span>Page {page}</span>
      <Button variant="secondary" disabled={!hasNext} onClick={() => onChange?.(page + 1)}>Next</Button>
    </nav>
  );
}
'''

API_CLIENT = r'''export const GATEWAY = 'http://localhost:18000';
export const TOKEN_KEY = 'appbuilder.jwt';

export class ApiError extends Error {
  constructor(status, message, payload = null) {
    super(message || `Request failed (${status})`);
    this.name = 'ApiError'; this.status = status; this.payload = payload;
  }
}

export function getToken() { return localStorage.getItem(TOKEN_KEY); }
export function setToken(token) { token ? localStorage.setItem(TOKEN_KEY, token) : localStorage.removeItem(TOKEN_KEY); }
export function logout() { localStorage.removeItem(TOKEN_KEY); }

export async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (options.body != null && !(options.body instanceof FormData)) {
    headers['Content-Type'] = headers['Content-Type'] || 'application/json';
  }
  const response = await fetch(GATEWAY + path, { ...options, headers });
  const contentType = response.headers.get('content-type') || '';
  const payload = response.status === 204 ? null : contentType.includes('application/json')
    ? await response.json() : await response.text();
  if (!response.ok) {
    const message = typeof payload === 'string' ? payload : payload?.error || payload?.message;
    throw new ApiError(response.status, message, payload);
  }
  return payload;
}
'''

ASYNC_HOOK = r'''import { useCallback, useState } from 'react';

export function useAsync(initialData = null) {
  const [state, setState] = useState({ data: initialData, loading: false, error: null });
  const run = useCallback(async (operation) => {
    setState(previous => ({ ...previous, loading: true, error: null }));
    try {
      const data = await operation();
      setState({ data, loading: false, error: null });
      return data;
    } catch (error) {
      setState(previous => ({ ...previous, loading: false, error }));
      throw error;
    }
  }, []);
  const reset = useCallback(() => setState({ data: initialData, loading: false, error: null }), [initialData]);
  return { ...state, run, reset, setData: data => setState({ data, loading: false, error: null }) };
}
'''

KIT_CSS = r''':root {
  --ab-bg: #08111f; --ab-panel: #101c2f; --ab-panel-strong: #17263d; --ab-line: rgba(148,163,184,.2);
  --ab-text: #f8fafc; --ab-muted: #94a3b8; --ab-brand: #22d3ee; --ab-brand-strong: #0891b2;
  --ab-good: #4ade80; --ab-warn: #fbbf24; --ab-danger: #fb7185; --ab-radius: 16px;
}
.ab-shell { min-height: 100vh; color: var(--ab-text); background: var(--ab-bg); }
.ab-shell--with-sidebar { display: grid; grid-template-columns: minmax(220px, 280px) 1fr; }
.ab-sidebar { padding: 24px; border-right: 1px solid var(--ab-line); background: var(--ab-panel); }
.ab-main { width: min(1180px, 100%); margin: 0 auto; padding: clamp(22px, 4vw, 52px); }
.ab-page-header, .ab-card-header, .ab-actions, .ab-pagination { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.ab-page-header { margin-bottom: 28px; align-items: flex-end; }
.ab-page-header h1, .ab-card-header h2, .ab-card h2, .ab-card h3 { margin: 0; }
.ab-page-header p, .ab-card-header p, .ab-card p { color: var(--ab-muted); }
.ab-eyebrow { margin-bottom: 7px; color: var(--ab-brand); font-size: 11px; font-weight: 850; letter-spacing: .13em; text-transform: uppercase; }
.ab-card { padding: 20px; border: 1px solid var(--ab-line); border-radius: var(--ab-radius); background: linear-gradient(145deg,rgba(255,255,255,.045),rgba(255,255,255,.018)); box-shadow: 0 18px 48px rgba(0,0,0,.18); }
.ab-stack { display: grid; gap: 16px; }
.ab-grid { display: grid; grid-template-columns: repeat(auto-fit,minmax(240px,1fr)); gap: 16px; }
.ab-button, .ab-icon-button { display: inline-flex; align-items: center; justify-content: center; gap: 8px; border: 0; cursor: pointer; font: inherit; font-weight: 800; }
.ab-button { min-height: 42px; padding: 9px 16px; border-radius: 11px; color: #04121a; background: linear-gradient(135deg,var(--ab-brand),#a78bfa); }
.ab-button--secondary { color: var(--ab-text); border: 1px solid var(--ab-line); background: var(--ab-panel-strong); }
.ab-button--ghost { color: var(--ab-muted); background: transparent; }
.ab-button--danger { color: white; background: #be123c; }
.ab-button--sm { min-height: 34px; padding: 6px 10px; font-size: 12px; }
.ab-button:disabled { opacity: .5; cursor: not-allowed; }
.ab-icon-button { width: 34px; height: 34px; border-radius: 10px; color: var(--ab-muted); background: rgba(148,163,184,.12); }
.ab-spinner { width: 14px; height: 14px; border: 2px solid rgba(255,255,255,.45); border-top-color: currentColor; border-radius: 50%; animation: ab-spin .8s linear infinite; }
@keyframes ab-spin { to { transform: rotate(360deg); } }
.ab-badge { display: inline-flex; padding: 4px 8px; border-radius: 99px; color: var(--ab-muted); background: rgba(148,163,184,.14); font-size: 10px; font-weight: 850; }
.ab-badge--success { color: var(--ab-good); background: rgba(74,222,128,.12); }.ab-badge--warning { color: var(--ab-warn); background: rgba(251,191,36,.12); }.ab-badge--danger { color: var(--ab-danger); background: rgba(251,113,133,.12); }
.ab-field { display: grid; gap: 7px; }.ab-field__label { font-size: 12px; font-weight: 800; }.ab-field__hint,.ab-field__error { color: var(--ab-muted); font-size: 11px; }.ab-field__error { color: var(--ab-danger); }
.ab-input { width: 100%; min-height: 43px; padding: 10px 12px; border: 1px solid var(--ab-line); border-radius: 11px; outline: none; color: var(--ab-text); background: rgba(2,6,23,.55); font: inherit; }
.ab-input:focus { border-color: var(--ab-brand); box-shadow: 0 0 0 3px rgba(34,211,238,.1); }.ab-textarea { min-height: 110px; resize: vertical; }
.ab-notice { padding: 13px 15px; display: flex; justify-content: space-between; gap: 12px; border: 1px solid rgba(34,211,238,.25); border-radius: 12px; color: #cffafe; background: rgba(34,211,238,.08); }.ab-notice strong { display: block; }.ab-notice p { margin: 4px 0 0; color: inherit; opacity: .82; }
.ab-notice--success { color: #dcfce7; border-color: rgba(74,222,128,.3); background: rgba(74,222,128,.08); }.ab-notice--warning { color: #fef3c7; border-color: rgba(251,191,36,.3); background: rgba(251,191,36,.08); }.ab-notice--danger { color: #ffe4e6; border-color: rgba(251,113,133,.3); background: rgba(251,113,133,.08); }
.ab-empty { min-height: 210px; display: grid; place-content: center; justify-items: center; text-align: center; }.ab-empty__icon { width: 54px; height: 54px; display: grid; place-items: center; border-radius: 16px; color: var(--ab-brand); background: rgba(34,211,238,.1); font-size: 25px; }.ab-empty h3 { margin: 13px 0 0; }.ab-empty p { max-width: 400px; margin: 7px 0 15px; color: var(--ab-muted); }
.ab-skeleton { display: grid; gap: 11px; }.ab-skeleton span { height: 16px; border-radius: 7px; background: linear-gradient(90deg,rgba(148,163,184,.1),rgba(148,163,184,.25),rgba(148,163,184,.1)); background-size: 200% 100%; animation: ab-shimmer 1.4s infinite; }.ab-skeleton span:last-child { width: 62%; } @keyframes ab-shimmer { to { background-position: -200% 0; } }
.ab-avatar { width: 38px; height: 38px; display: inline-grid; place-items: center; border-radius: 50%; object-fit: cover; color: #04121a; background: linear-gradient(135deg,var(--ab-brand),#a78bfa); font-size: 11px; font-weight: 900; }.ab-avatar--sm { width: 28px; height: 28px; }.ab-avatar--lg { width: 52px; height: 52px; }
.ab-modal-backdrop { position: fixed; inset: 0; z-index: 50; display: grid; place-items: center; padding: 20px; background: rgba(2,6,23,.76); backdrop-filter: blur(8px); }.ab-modal { width: min(560px,100%); overflow: hidden; border: 1px solid var(--ab-line); border-radius: 18px; background: var(--ab-panel); box-shadow: 0 28px 80px rgba(0,0,0,.5); }.ab-modal__header,.ab-modal__footer { padding: 16px 18px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--ab-line); }.ab-modal__footer { justify-content: flex-end; border-top: 1px solid var(--ab-line); border-bottom: 0; }.ab-modal__body { padding: 18px; }
.ab-search { display: grid; grid-template-columns: 1fr auto; gap: 9px; }.ab-pagination { margin-top: 20px; justify-content: center; color: var(--ab-muted); }
@media (max-width: 760px) { .ab-shell--with-sidebar { grid-template-columns: 1fr; }.ab-sidebar { border-right: 0; border-bottom: 1px solid var(--ab-line); }.ab-page-header { align-items: flex-start; flex-direction: column; }.ab-main { padding: 20px; } }
'''
