import { Button, Card, EmptyState, Field, Skeleton, StatusNotice, TextArea, TextInput } from './AppBuilderUI.jsx';

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
