import { useEffect, useState } from 'react';
import {
  AppShell,
  Avatar,
  Badge,
  Button,
  Card,
  CardHeader,
  Field,
  Modal,
  PageHeader,
  StatusNotice,
  TextArea,
  TextInput,
  cx,
} from './components/AppBuilderUI.jsx';
import { AsyncBoundary, AuthForm, SearchBox } from './components/AppBuilderPatterns.jsx';
import { api, GATEWAY, getToken, logout as clearToken, setToken } from './lib/api.js';
import { useAsync } from './hooks/useAsync.js';

const POLLABLE_STATUSES = new Set(['QUEUED', 'RUNNING']);
const DIFFICULTIES = ['ALL', 'EASY', 'MEDIUM', 'HARD'];

function createEmptyCase(hidden = false) {
  return {
    id: `case-${Date.now()}-${Math.random().toString(16).slice(2)}`,
    inputText: '{\n  \n}',
    outputText: 'null',
    hidden,
  };
}

function createEmptyAdminDraft() {
  return {
    id: '',
    title: '',
    description: '',
    difficulty: 'EASY',
    tagsText: 'array',
    javascriptStub: 'function solve(input) {\n  return input;\n}',
    testCases: [createEmptyCase(false), createEmptyCase(true)],
  };
}

function sleep(ms) {
  return new Promise(resolve => window.setTimeout(resolve, ms));
}

function createIdempotencyKey(prefix) {
  if (globalThis.crypto?.randomUUID) return `${prefix}-${globalThis.crypto.randomUUID()}`;
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function formatError(error) {
  if (!error) return '';
  if (error.name === 'TypeError') return 'Network or CORS error while contacting the gateway.';
  return String(error.message || error);
}

function normalizeProblems(payload) {
  if (Array.isArray(payload)) return payload;
  if (Array.isArray(payload?.items)) return payload.items;
  if (Array.isArray(payload?.problems)) return payload.problems;
  return [];
}

function getDifficulty(problem) {
  return String(problem?.difficulty || 'UNKNOWN').toUpperCase();
}

function getDifficultyTone(difficulty) {
  if (difficulty === 'EASY') return 'success';
  if (difficulty === 'MEDIUM') return 'warning';
  if (difficulty === 'HARD') return 'danger';
  return 'neutral';
}

function getSubmissionTone(status) {
  if (status === 'ACCEPTED') return 'success';
  if (status === 'WRONG_ANSWER' || status === 'RUNTIME_ERROR' || status === 'COMPILATION_ERROR') return 'danger';
  if (status === 'QUEUED' || status === 'RUNNING') return 'warning';
  return 'neutral';
}

function getExamples(problem) {
  if (Array.isArray(problem?.examples)) return problem.examples;
  if (Array.isArray(problem?.testCases)) return problem.testCases.filter(testCase => !testCase.hidden);
  return [];
}

function getJavaScriptStub(problem) {
  return problem?.codeStubs?.javascript || 'function solve(input) {\n  return input;\n}';
}

function getRoles(viewer) {
  return Array.isArray(viewer?.roles) && viewer.roles.length ? viewer.roles : ['ANON'];
}

function safeStringify(value) {
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function mapAdminProblemToDraft(problem) {
  return {
    id: String(problem?.id || ''),
    title: String(problem?.title || ''),
    description: String(problem?.description || ''),
    difficulty: String(problem?.difficulty || 'EASY').toUpperCase(),
    tagsText: Array.isArray(problem?.tags) ? problem.tags.join(', ') : '',
    javascriptStub: String(problem?.codeStubs?.javascript || ''),
    testCases: Array.isArray(problem?.testCases) && problem.testCases.length
      ? problem.testCases.map(testCase => ({
          id: `case-${Math.random().toString(16).slice(2)}`,
          inputText: safeStringify(testCase?.input ?? {}),
          outputText: safeStringify(testCase?.output ?? null),
          hidden: Boolean(testCase?.hidden),
        }))
      : [createEmptyCase(false), createEmptyCase(true)],
  };
}

function buildAdminPayload(draft) {
  const tags = draft.tagsText
    .split(',')
    .map(tag => tag.trim())
    .filter(Boolean);

  const testCases = draft.testCases.map((testCase, index) => {
    try {
      return {
        input: JSON.parse(testCase.inputText),
        output: JSON.parse(testCase.outputText),
        hidden: Boolean(testCase.hidden),
      };
    } catch (error) {
      throw new Error(`Test case ${index + 1} contains invalid JSON.`);
    }
  });

  if (!draft.id.trim()) throw new Error('Problem id is required.');
  if (!draft.title.trim()) throw new Error('Problem title is required.');
  if (!draft.description.trim()) throw new Error('Problem description is required.');
  if (!draft.javascriptStub.trim()) throw new Error('JavaScript starter code is required.');
  if (!testCases.length) throw new Error('At least one test case is required.');

  return {
    id: draft.id.trim(),
    title: draft.title.trim(),
    description: draft.description.trim(),
    difficulty: draft.difficulty,
    tags,
    codeStubs: { javascript: draft.javascriptStub },
    testCases,
  };
}

function StatsStrip({ problems }) {
  const easy = problems.filter(problem => getDifficulty(problem) === 'EASY').length;
  const medium = problems.filter(problem => getDifficulty(problem) === 'MEDIUM').length;
  const hard = problems.filter(problem => getDifficulty(problem) === 'HARD').length;

  return (
    <div className="lc-stats">
      <div><span>Problems</span><strong>{problems.length}</strong></div>
      <div><span>Easy</span><strong>{easy}</strong></div>
      <div><span>Medium</span><strong>{medium}</strong></div>
      <div><span>Hard</span><strong>{hard}</strong></div>
    </div>
  );
}

function Sidebar({ viewer, selectedSection, onSectionChange, problemCount }) {
  const sections = [
    { id: 'practice', label: 'Practice', meta: `${problemCount} problems` },
    { id: 'submissions', label: 'Submissions', meta: 'Latest judge result' },
    { id: 'extras', label: 'Roadmap', meta: 'What is available next' },
  ];

  return (
    <div className="lc-sidebar">
      <div className="lc-brand">
        <div className="lc-brand__mark">{'</>'}</div>
        <div>
          <strong>AlgoForge</strong>
          <p>LeetCode-style practice wired to the live gateway.</p>
        </div>
      </div>

      <Card className="lc-profile-card">
        <div className="lc-profile-card__row">
          <Avatar name={viewer?.username || 'Guest'} size="lg" />
          <div>
            <strong>{viewer?.username || 'Guest mode'}</strong>
            <p>{viewer ? 'Signed in for submissions and admin actions.' : 'Browse problems anonymously. Sign in to submit.'}</p>
          </div>
        </div>
        <div className="lc-role-strip">
          {getRoles(viewer).map(role => <Badge key={role}>{role}</Badge>)}
        </div>
      </Card>

      <nav className="lc-nav" aria-label="Workspace sections">
        {sections.map(section => (
          <button
            key={section.id}
            type="button"
            className={cx('lc-nav__item', selectedSection === section.id && 'is-active')}
            onClick={() => onSectionChange(section.id)}
          >
            <strong>{section.label}</strong>
            <span>{section.meta}</span>
          </button>
        ))}
      </nav>

      <Card className="lc-sidebar-card">
        <h3>Backend map</h3>
        <p>Reads and submits use `leetcode-service`; identity uses `auth-service`.</p>
      </Card>
    </div>
  );
}

function ProblemList({ problems, selectedProblemId, onSelect }) {
  return (
    <div className="lc-problem-list">
      {problems.map(problem => {
        const difficulty = getDifficulty(problem);
        const tags = Array.isArray(problem?.tags) ? problem.tags : [];
        const isActive = selectedProblemId === problem.id;

        return (
          <button
            key={problem.id}
            type="button"
            className={cx('lc-problem-row', isActive && 'is-active')}
            onClick={() => onSelect(problem.id)}
          >
            <div className="lc-problem-row__top">
              <strong>{problem.title || problem.id}</strong>
              <Badge tone={getDifficultyTone(difficulty)}>{difficulty}</Badge>
            </div>
            <div className="lc-problem-row__meta">
              <span>{problem.id}</span>
              <span>{tags.slice(0, 3).join(' • ') || 'No tags'}</span>
            </div>
          </button>
        );
      })}
    </div>
  );
}

function ExampleCard({ example, index }) {
  return (
    <Card className="lc-example-card">
      <CardHeader title={`Example ${index + 1}`} />
      <div className="lc-code-block">
        <strong>Input</strong>
        <pre>{safeStringify(example?.input ?? example)}</pre>
      </div>
      {example && typeof example === 'object' && 'output' in example ? (
        <div className="lc-code-block">
          <strong>Output</strong>
          <pre>{safeStringify(example?.output)}</pre>
        </div>
      ) : null}
      {example?.explanation ? <p>{example.explanation}</p> : null}
    </Card>
  );
}

function SubmissionCard({ submission, busy, error }) {
  return (
    <Card className="lc-submission-card">
      <CardHeader
        title="Latest submission"
        description="The judge result is polled from `/leetcode/submissions/{id}`."
        actions={submission?.status ? <Badge tone={getSubmissionTone(String(submission.status).toUpperCase())}>{String(submission.status).toUpperCase()}</Badge> : null}
      />
      {busy ? <StatusNotice tone="warning" title="Judge is running">Polling for the latest submission state.</StatusNotice> : null}
      {error ? <StatusNotice tone="danger" title="Submission failed">{error}</StatusNotice> : null}
      {submission ? (
        <div className="lc-submission-grid">
          <div><span>Submission id</span><strong>{submission.id || submission.submissionId || 'Unknown'}</strong></div>
          <div><span>Language</span><strong>{submission.language || 'javascript'}</strong></div>
          <div><span>Runtime</span><strong>{submission.runtimeMs ?? submission.runtime ?? 'n/a'}</strong></div>
          <div><span>Memory</span><strong>{submission.memoryKb ?? submission.memory ?? 'n/a'}</strong></div>
        </div>
      ) : (
        <p className="lc-muted">No submission yet. Sign in, open a problem, and run the JavaScript stub.</p>
      )}
      {submission ? (
        <div className="lc-code-block">
          <strong>Judge payload</strong>
          <pre>{safeStringify(submission)}</pre>
        </div>
      ) : null}
    </Card>
  );
}

function PlaceholderCard({ title, description }) {
  return (
    <Card className="lc-placeholder-card">
      <Badge tone="warning">Being developed — backend not available yet</Badge>
      <h3>{title}</h3>
      <p>{description}</p>
    </Card>
  );
}

function AdminProblemModal({
  open,
  mode,
  draft,
  busy,
  loadBusy,
  error,
  onClose,
  onChange,
  onAddCase,
  onRemoveCase,
  onSave,
}) {
  async function submit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    await onSave(form);
  }

  return (
    <Modal
      open={open}
      title={mode === 'edit' ? 'Edit problem' : 'Create problem'}
      onClose={busy ? undefined : onClose}
      footer={(
        <>
          <Button variant="ghost" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button type="submit" form="admin-problem-form" busy={busy}>
            {mode === 'edit' ? 'Save changes' : 'Create problem'}
          </Button>
        </>
      )}
    >
      {loadBusy ? <StatusNotice title="Loading admin problem">Fetching the full hidden test case payload.</StatusNotice> : null}
      {error ? <StatusNotice tone="danger" title="Admin action failed">{error}</StatusNotice> : null}
      <form id="admin-problem-form" className="lc-admin-form" onSubmit={submit}>
        <div className="lc-form-grid">
          <Field label="Problem id" hint="Used in the problem URL and admin update path.">
            <TextInput value={draft.id} onChange={event => onChange('id', event.target.value)} required />
          </Field>
          <Field label="Difficulty">
            <select className="ab-input" value={draft.difficulty} onChange={event => onChange('difficulty', event.target.value)}>
              {DIFFICULTIES.filter(item => item !== 'ALL').map(item => <option key={item} value={item}>{item}</option>)}
            </select>
          </Field>
        </div>

        <Field label="Title">
          <TextInput value={draft.title} onChange={event => onChange('title', event.target.value)} required />
        </Field>

        <Field label="Tags" hint="Comma-separated tags.">
          <TextInput value={draft.tagsText} onChange={event => onChange('tagsText', event.target.value)} />
        </Field>

        <Field label="Description">
          <TextArea value={draft.description} onChange={event => onChange('description', event.target.value)} required />
        </Field>

        <Field label="JavaScript starter code">
          <TextArea className="lc-editor" value={draft.javascriptStub} onChange={event => onChange('javascriptStub', event.target.value)} required />
        </Field>

        <div className="lc-case-stack">
          <div className="lc-case-stack__header">
            <div>
              <h3>Test cases</h3>
              <p>Values are parsed as JSON and sent to the admin endpoints exactly as entered.</p>
            </div>
            <div className="lc-case-stack__actions">
              <Button variant="secondary" type="button" onClick={() => onAddCase(false)}>Add visible case</Button>
              <Button variant="ghost" type="button" onClick={() => onAddCase(true)}>Add hidden case</Button>
            </div>
          </div>

          {draft.testCases.map((testCase, index) => (
            <Card key={testCase.id} className="lc-case-card">
              <div className="lc-case-card__header">
                <strong>Case {index + 1}</strong>
                <div className="lc-case-card__actions">
                  <label className="lc-checkbox">
                    <input
                      type="checkbox"
                      checked={testCase.hidden}
                      onChange={event => onChange('testCases', draft.testCases.map(item => item.id === testCase.id ? { ...item, hidden: event.target.checked } : item))}
                    />
                    Hidden
                  </label>
                  <Button variant="ghost" size="sm" type="button" onClick={() => onRemoveCase(testCase.id)} disabled={draft.testCases.length === 1}>
                    Remove
                  </Button>
                </div>
              </div>
              <div className="lc-form-grid">
                <Field label="Input JSON">
                  <TextArea
                    value={testCase.inputText}
                    onChange={event => onChange('testCases', draft.testCases.map(item => item.id === testCase.id ? { ...item, inputText: event.target.value } : item))}
                    required
                  />
                </Field>
                <Field label="Output JSON">
                  <TextArea
                    value={testCase.outputText}
                    onChange={event => onChange('testCases', draft.testCases.map(item => item.id === testCase.id ? { ...item, outputText: event.target.value } : item))}
                    required
                  />
                </Field>
              </div>
            </Card>
          ))}
        </div>
      </form>
    </Modal>
  );
}

export default function App() {
  const viewerState = useAsync(null);
  const problemsState = useAsync([]);
  const problemDetailState = useAsync(null);
  const [authMode, setAuthMode] = useState('login');
  const [authBusy, setAuthBusy] = useState(false);
  const [authError, setAuthError] = useState('');
  const [selectedProblemId, setSelectedProblemId] = useState(null);
  const [search, setSearch] = useState('');
  const [difficulty, setDifficulty] = useState('ALL');
  const [editorCode, setEditorCode] = useState('');
  const [submission, setSubmission] = useState(null);
  const [submissionBusy, setSubmissionBusy] = useState(false);
  const [submissionError, setSubmissionError] = useState('');
  const [activeSection, setActiveSection] = useState('practice');
  const [adminOpen, setAdminOpen] = useState(false);
  const [adminMode, setAdminMode] = useState('create');
  const [adminDraft, setAdminDraft] = useState(createEmptyAdminDraft());
  const [adminBusy, setAdminBusy] = useState(false);
  const [adminLoadBusy, setAdminLoadBusy] = useState(false);
  const [adminError, setAdminError] = useState('');

  const viewer = viewerState.data;
  const problems = problemsState.data || [];
  const selectedProblem = problemDetailState.data;
  const isAdmin = Boolean(viewer?.roles?.includes('ADMIN'));

  async function loadViewer() {
    if (!getToken()) {
      viewerState.reset();
      return null;
    }

    try {
      return await viewerState.run(() => api('/auth/me'));
    } catch (error) {
      if (error.status === 401) {
        clearToken();
        viewerState.reset();
        return null;
      }
      throw error;
    }
  }

  async function loadProblems(preferredProblemId = null) {
    const items = await problemsState.run(async () => normalizeProblems(await api('/leetcode/problems')));
    const nextSelected = preferredProblemId || selectedProblemId || items[0]?.id || null;
    setSelectedProblemId(nextSelected);
    return items;
  }

  useEffect(() => {
    loadProblems().catch(() => {});
    loadViewer().catch(() => {});
  }, []);

  useEffect(() => {
    if (!selectedProblemId) return;

    let cancelled = false;

    problemDetailState.run(() => api(`/leetcode/problems/${encodeURIComponent(selectedProblemId)}`))
      .then(problem => {
        if (cancelled) return;
        setEditorCode(getJavaScriptStub(problem));
      })
      .catch(() => {
        if (cancelled) return;
        setEditorCode('');
      });

    return () => {
      cancelled = true;
    };
  }, [selectedProblemId]);

  const filteredProblems = problems.filter(problem => {
    const text = `${problem?.id || ''} ${problem?.title || ''} ${(problem?.tags || []).join(' ')}`.toLowerCase();
    const matchesSearch = !search.trim() || text.includes(search.trim().toLowerCase());
    const matchesDifficulty = difficulty === 'ALL' || getDifficulty(problem) === difficulty;
    return matchesSearch && matchesDifficulty;
  });

  async function authenticate(path, body) {
    const response = await fetch(GATEWAY + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });

    const contentType = response.headers.get('content-type') || '';
    const payload = response.status === 204 ? null : contentType.includes('application/json')
      ? await response.json()
      : await response.text();

    if (!response.ok) {
      const message = typeof payload === 'string' ? payload : payload?.error || payload?.message;
      throw new Error(message || `Authentication failed (${response.status})`);
    }

    return payload;
  }

  async function handleAuthSubmit(values, form) {
    setAuthBusy(true);
    setAuthError('');

    try {
      if (authMode === 'register') {
        await authenticate('/auth/register', values);
      }

      const loginResult = await authenticate('/auth/login', values);
      setToken(loginResult.access_token);
      await loadViewer();
      form.reset();
    } catch (error) {
      setAuthError(formatError(error));
    } finally {
      setAuthBusy(false);
    }
  }

  async function handleLogout() {
    clearToken();
    viewerState.reset();
    setSubmission(null);
    setSubmissionError('');
  }

  async function handleSubmitSolution() {
    if (!selectedProblemId || !viewer) return;

    setSubmissionBusy(true);
    setSubmissionError('');

    try {
      const submissionCreate = await api(`/leetcode/problems/${encodeURIComponent(selectedProblemId)}/submit`, {
        method: 'POST',
        headers: { 'Idempotency-Key': createIdempotencyKey('submission') },
        body: JSON.stringify({ language: 'javascript', code: editorCode }),
      });

      const submissionId = submissionCreate?.id || submissionCreate?.submissionId;
      if (!submissionId) throw new Error('The submit endpoint did not return a submission id.');

      let latest = submissionCreate;
      setSubmission(submissionCreate);

      for (let attempt = 0; attempt < 25; attempt += 1) {
        latest = await api(`/leetcode/submissions/${encodeURIComponent(submissionId)}`);
        setSubmission(latest);
        const status = String(latest?.status || '').toUpperCase();
        if (!POLLABLE_STATUSES.has(status)) {
          setSubmissionBusy(false);
          return;
        }
        await sleep(1200);
      }

      throw new Error('Timed out while waiting for the judge to finish.');
    } catch (error) {
      setSubmissionError(formatError(error));
    } finally {
      setSubmissionBusy(false);
    }
  }

  function openCreateProblem() {
    setAdminMode('create');
    setAdminDraft(createEmptyAdminDraft());
    setAdminError('');
    setAdminLoadBusy(false);
    setAdminOpen(true);
  }

  async function openEditProblem() {
    if (!selectedProblemId) return;
    setAdminMode('edit');
    setAdminOpen(true);
    setAdminLoadBusy(true);
    setAdminError('');

    try {
      const problem = await api(`/leetcode/admin/problems/${encodeURIComponent(selectedProblemId)}`);
      setAdminDraft(mapAdminProblemToDraft(problem));
    } catch (error) {
      setAdminError(formatError(error));
    } finally {
      setAdminLoadBusy(false);
    }
  }

  async function handleSaveProblem() {
    setAdminBusy(true);
    setAdminError('');

    try {
      const payload = buildAdminPayload(adminDraft);
      const path = adminMode === 'edit'
        ? `/leetcode/admin/problems/${encodeURIComponent(payload.id)}`
        : '/leetcode/admin/problems';

      await api(path, {
        method: adminMode === 'edit' ? 'PUT' : 'POST',
        body: JSON.stringify(payload),
      });

      await loadProblems(payload.id);
      setAdminOpen(false);
    } catch (error) {
      setAdminError(formatError(error));
    } finally {
      setAdminBusy(false);
    }
  }

  function updateAdminDraft(field, value) {
    setAdminDraft(previous => ({ ...previous, [field]: value }));
  }

  function addCase(hidden) {
    setAdminDraft(previous => ({ ...previous, testCases: [...previous.testCases, createEmptyCase(hidden)] }));
  }

  function removeCase(caseId) {
    setAdminDraft(previous => ({ ...previous, testCases: previous.testCases.filter(testCase => testCase.id !== caseId) }));
  }

  const problemExamples = getExamples(selectedProblem);

  return (
    <AppShell
      sidebar={(
        <Sidebar
          viewer={viewer}
          selectedSection={activeSection}
          onSectionChange={setActiveSection}
          problemCount={problems.length}
        />
      )}
      className="leetcode-shell"
    >
      <PageHeader
        eyebrow="LeetCode clone"
        title="Solve algorithm problems against the live judge"
        description="Browse problems, authenticate through Kong, submit JavaScript solutions, and manage problem content when the backend marks you as an admin."
        actions={(
          <div className="lc-header-actions">
            {viewer ? (
              <>
                <Badge tone="success">{viewer.username}</Badge>
                <Button variant="secondary" onClick={handleLogout}>Log out</Button>
              </>
            ) : (
              <Badge tone="warning">Auth required for submit</Badge>
            )}
          </div>
        )}
      />

      {viewerState.error ? <StatusNotice tone="danger" title="Profile lookup failed">{formatError(viewerState.error)}</StatusNotice> : null}

      <section className="lc-hero">
        <Card className="lc-hero__primary">
          <div className="lc-hero__copy">
            <Badge tone="success">leetcode-service</Badge>
            <h2>Problem list, detail, submit, and admin APIs are wired end to end.</h2>
            <p>The solver reads public problem data, keeps hidden cases out of the practice view, and polls submission status until the judge finishes.</p>
          </div>
          <StatsStrip problems={problems} />
        </Card>

        <div className="lc-hero__auth">
          <Card>
            <CardHeader
              title={viewer ? 'Your account is active' : authMode === 'register' ? 'Create an account' : 'Sign in to submit'}
              description={viewer ? 'JWT is stored locally and attached on protected calls.' : 'The auth flow uses `/auth/register`, `/auth/login`, and `/auth/me`.'}
              actions={!viewer ? (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    setAuthMode(previous => previous === 'login' ? 'register' : 'login');
                    setAuthError('');
                  }}
                >
                  {authMode === 'login' ? 'Need an account?' : 'Have an account?'}
                </Button>
              ) : null}
            />
            {viewer ? (
              <div className="lc-auth-summary">
                <div>
                  <span>Roles</span>
                  <div className="lc-role-strip">
                    {getRoles(viewer).map(role => <Badge key={role}>{role}</Badge>)}
                  </div>
                </div>
                <p className="lc-muted">Protected requests now include `Authorization: Bearer &lt;jwt&gt;`.</p>
              </div>
            ) : (
              <AuthForm mode={authMode} busy={authBusy} error={authError} onSubmit={handleAuthSubmit} />
            )}
          </Card>
        </div>
      </section>

      <section className="lc-layout">
        <Card className="lc-panel">
          <CardHeader title="Problem set" description="Public list from `GET /leetcode/problems`." />
          <div className="lc-toolbar">
            <SearchBox value={search} onChange={setSearch} onSubmit={() => {}} placeholder="Search by title, slug, or tag" />
            <label className="lc-filter">
              <span>Difficulty</span>
              <select className="ab-input" value={difficulty} onChange={event => setDifficulty(event.target.value)}>
                {DIFFICULTIES.map(item => <option key={item} value={item}>{item}</option>)}
              </select>
            </label>
          </div>

          <AsyncBoundary
            loading={problemsState.loading}
            error={problemsState.error}
            empty={!filteredProblems.length}
            emptyTitle="No problems matched this filter"
            emptyDescription="Try a broader search or reload once the service has data."
          >
            <ProblemList
              problems={filteredProblems}
              selectedProblemId={selectedProblemId}
              onSelect={setSelectedProblemId}
            />
          </AsyncBoundary>
        </Card>

        <div className="lc-main-column">
          <Card className="lc-panel">
            <CardHeader
              title={selectedProblem?.title || 'Problem detail'}
              description={selectedProblem?.id || 'Select a problem to load its statement and code stub.'}
              actions={selectedProblem ? <Badge tone={getDifficultyTone(getDifficulty(selectedProblem))}>{getDifficulty(selectedProblem)}</Badge> : null}
            />

            <AsyncBoundary
              loading={problemDetailState.loading}
              error={problemDetailState.error}
              empty={!selectedProblem}
              emptyTitle="Pick a problem"
              emptyDescription="The detail view loads `GET /leetcode/problems/{id}` for the selected row."
            >
              <div className="lc-problem-detail">
                <div className="lc-problem-detail__copy">
                  <p>{selectedProblem?.description}</p>
                  <div className="lc-tag-row">
                    {(selectedProblem?.tags || []).map(tag => <Badge key={tag}>{tag}</Badge>)}
                  </div>
                </div>

                <div className="lc-example-grid">
                  {problemExamples.length
                    ? problemExamples.map((example, index) => <ExampleCard key={index} example={example} index={index} />)
                    : <Card><p className="lc-muted">No visible examples were returned for this problem.</p></Card>}
                </div>
              </div>
            </AsyncBoundary>
          </Card>

          <Card className="lc-panel">
            <CardHeader
              title="JavaScript solver"
              description="Submissions call `POST /leetcode/problems/{id}/submit` with a unique `Idempotency-Key` header."
              actions={isAdmin ? (
                <div className="lc-header-actions">
                  <Button variant="secondary" size="sm" onClick={openCreateProblem}>New problem</Button>
                  <Button variant="secondary" size="sm" onClick={openEditProblem} disabled={!selectedProblemId}>Edit current</Button>
                </div>
              ) : null}
            />
            {!viewer ? <StatusNotice tone="warning" title="Sign in to submit">Problem reads are available now, but judge submissions require authentication.</StatusNotice> : null}
            <Field label="Solution code" hint="The editor is seeded from `codeStubs.javascript` when a problem loads.">
              <TextArea className="lc-editor" value={editorCode} onChange={event => setEditorCode(event.target.value)} />
            </Field>
            <div className="lc-editor-actions">
              <Badge>language: javascript</Badge>
              <Button onClick={handleSubmitSolution} busy={submissionBusy} disabled={!viewer || !selectedProblemId || !editorCode.trim()}>
                Run submission
              </Button>
            </div>
          </Card>

          <SubmissionCard submission={submission} busy={submissionBusy} error={submissionError} />
        </div>
      </section>

      <section className="lc-roadmap">
        <PlaceholderCard
          title="Contests and leaderboards"
          description="The backend exposes leaderboard lookup by competition id and competition creation, but there is no public competition listing flow yet."
        />
        <PlaceholderCard
          title="Discussion and solution articles"
          description="A problem-specific community backend is not available in the current plug inventory, so this area stays visible but disabled."
        />
      </section>

      <AdminProblemModal
        open={adminOpen}
        mode={adminMode}
        draft={adminDraft}
        busy={adminBusy}
        loadBusy={adminLoadBusy}
        error={adminError}
        onClose={() => setAdminOpen(false)}
        onChange={updateAdminDraft}
        onAddCase={addCase}
        onRemoveCase={removeCase}
        onSave={handleSaveProblem}
      />
    </AppShell>
  );
}
