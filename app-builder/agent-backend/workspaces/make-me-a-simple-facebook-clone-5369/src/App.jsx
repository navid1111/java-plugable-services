import { useEffect, useState } from 'react';
import {
  AppShell,
  Avatar,
  Badge,
  Button,
  Card,
  CardHeader,
  EmptyState,
  Modal,
  PageHeader,
  StatusNotice,
} from './components/AppBuilderUI.jsx';
import { AsyncBoundary, AuthForm, Composer, SearchBox } from './components/AppBuilderPatterns.jsx';
import { useAsync } from './hooks/useAsync.js';
import { ApiError, api, getToken, logout, setToken, GATEWAY } from './lib/api.js';

const COMMENT_TARGET_MISSING = 'target does not exist or is deleted';
const TARGET_RETRY_DELAYS_MS = [0, 250, 500, 900];

function normalizeFeed(payload) {
  if (Array.isArray(payload)) return { items: payload, nextCursor: null, sourceVersionWatermark: null };
  return {
    items: Array.isArray(payload?.items) ? payload.items : [],
    nextCursor: payload?.nextCursor ?? null,
    sourceVersionWatermark: payload?.sourceVersionWatermark ?? null,
  };
}

function normalizeComments(payload) {
  if (Array.isArray(payload)) return payload;
  if (Array.isArray(payload?.items)) return payload.items;
  if (Array.isArray(payload?.comments)) return payload.comments;
  return [];
}

function normalizeSearch(payload) {
  if (Array.isArray(payload)) return payload;
  if (Array.isArray(payload?.items)) return payload.items;
  if (Array.isArray(payload?.results)) return payload.results;
  return [];
}

function wait(ms) {
  return new Promise(resolve => {
    setTimeout(resolve, ms);
  });
}

function formatDate(value) {
  if (!value) return 'Unknown time';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'Unknown time' : date.toLocaleString();
}

function getErrorMessage(error) {
  if (!error) return null;
  if (error instanceof ApiError) {
    if (error.status === 0) return 'The gateway did not respond. Check Kong and your CORS setup.';
    return `${error.status} ${error.message}`;
  }
  if (error instanceof TypeError) return `${error.message} The gateway may be unavailable or blocked by CORS.`;
  return String(error.message || error);
}

function getProfileName(profile) {
  return profile?.displayName || profile?.fullName || profile?.username || 'Member';
}

function getRoles(profile) {
  return Array.isArray(profile?.roles) ? profile.roles : [];
}

function getSearchItemKey(item, index) {
  return item?.id || item?.postId || item?.documentId || item?.targetId || `search-${index}`;
}

function getSearchTitle(item) {
  return item?.title || item?.author?.username || item?.username || item?.targetType || 'Search result';
}

function getSearchDescription(item) {
  return item?.snippet || item?.content || item?.summary || item?.text || 'No preview available.';
}

function DisabledFeatureCard({ title, description }) {
  return (
    <Card className="fb-placeholder-card">
      <CardHeader title={title} description={description} actions={<Badge>Not wired</Badge>} />
      <StatusNotice title="Being developed — backend not available yet">
        This surface is visible in the UI, but there is no supported backend contract for it yet.
      </StatusNotice>
      <Button variant="ghost" disabled>Unavailable</Button>
    </Card>
  );
}

function FeedPostCard({ item, onOpen }) {
  const degraded = Array.isArray(item?.degraded) ? item.degraded : [];
  return (
    <Card className="fb-post-card">
      <div className="fb-post-card__top">
        <div className="fb-post-card__identity">
          <Avatar name={item?.author?.username || 'User'} />
          <div>
            <strong>{item?.author?.username || 'Unknown user'}</strong>
            <div className="fb-post-card__meta">{formatDate(item?.post?.createdAt)}</div>
          </div>
        </div>
        <Badge tone="success">Live</Badge>
      </div>
      <p className="fb-post-card__content">{item?.post?.content || 'No content available.'}</p>
      <div className="fb-post-card__stats">
        <span>{item?.comments?.commentCount ?? 0} comments</span>
        <span>{item?.media?.mediaCount ?? 0} media</span>
        <span>v{item?.post?.version ?? '0'}</span>
      </div>
      {degraded.length ? (
        <StatusNotice title="Some sections are temporarily unavailable">
          {degraded.join(', ')}
        </StatusNotice>
      ) : null}
      <div className="fb-post-card__actions">
        <Button variant="secondary" onClick={() => onOpen(item?.post?.id)}>Open discussion</Button>
      </div>
    </Card>
  );
}

function CommentList({ comments }) {
  if (!comments.length) {
    return (
      <EmptyState
        icon="◌"
        title="No comments yet"
        description="Start the discussion on this post."
      />
    );
  }
  return (
    <div className="fb-comments">
      {comments.map(comment => (
        <Card key={comment.id || `${comment.author?.username}-${comment.createdAt}`} className="fb-comment-card">
          <div className="fb-comment-card__head">
            <div className="fb-post-card__identity">
              <Avatar name={comment.author?.username || 'User'} size="sm" />
              <div>
                <strong>{comment.author?.username || 'Unknown user'}</strong>
                <div className="fb-post-card__meta">{formatDate(comment.createdAt)}</div>
              </div>
            </div>
          </div>
          <p>{comment.content || 'No comment content available.'}</p>
        </Card>
      ))}
    </div>
  );
}

export default function App() {
  const [authMode, setAuthMode] = useState('login');
  const [profile, setProfile] = useState(null);
  const [authBusy, setAuthBusy] = useState(false);
  const [authError, setAuthError] = useState('');
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedPostId, setSelectedPostId] = useState(null);
  const [composerBusy, setComposerBusy] = useState(false);
  const [commentBusy, setCommentBusy] = useState(false);
  const [flash, setFlash] = useState(null);

  const feedState = useAsync({ items: [], nextCursor: null, sourceVersionWatermark: null });
  const searchState = useAsync([]);
  const detailState = useAsync(null);
  const commentsState = useAsync([]);

  const loggedIn = Boolean(profile && getToken());

  async function loginRequest(username, password) {
    const response = await fetch(GATEWAY + '/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
    const contentType = response.headers.get('content-type') || '';
    const payload = contentType.includes('application/json') ? await response.json() : await response.text();
    if (!response.ok) {
      const message = typeof payload === 'string' ? payload : payload?.error || payload?.message;
      throw new ApiError(response.status, message, payload);
    }
    setToken(payload.access_token);
    return payload;
  }

  async function loadProfile() {
    const me = await api('/auth/me');
    setProfile(me);
    return me;
  }

  async function loadFeed() {
    const payload = await feedState.run(async () => normalizeFeed(await api('/bff/feed')));
    return payload;
  }

  async function bootstrap() {
    if (!getToken()) return;
    try {
      await loadProfile();
      await loadFeed();
    } catch (error) {
      logout();
      setProfile(null);
      setFlash({ tone: 'danger', title: 'Session expired', message: getErrorMessage(error) });
    }
  }

  useEffect(() => {
    bootstrap();
  }, []);

  function closePost() {
    setSelectedPostId(null);
    detailState.reset();
    commentsState.reset();
  }

  async function refreshFeedAndDetail(postId = selectedPostId) {
    await loadFeed();
    if (postId) {
      await openPost(postId);
    }
  }

  async function ensureCommentTargetReady(postId) {
    for (const delay of TARGET_RETRY_DELAYS_MS) {
      if (delay) await wait(delay);
      try {
        await api(`/comments/targets/post/${postId}/summary`);
        return;
      } catch (error) {
        const message = String(error?.message || '').toLowerCase();
        if (message.includes(COMMENT_TARGET_MISSING) && delay !== TARGET_RETRY_DELAYS_MS[TARGET_RETRY_DELAYS_MS.length - 1]) {
          continue;
        }
        throw error;
      }
    }
  }

  async function openPost(postId) {
    if (!postId) return;
    setSelectedPostId(postId);
    await Promise.all([
      detailState.run(() => api(`/bff/posts/${postId}`)),
      commentsState.run(async () => normalizeComments(await api(`/comments/targets/post/${postId}`))),
    ]);
  }

  async function handleAuthSubmit(values) {
    setAuthBusy(true);
    setAuthError('');
    setFlash(null);
    try {
      if (authMode === 'register') {
        await api('/auth/register', {
          method: 'POST',
          body: JSON.stringify({ username: values.username, password: values.password }),
        });
      }
      await loginRequest(values.username, values.password);
      await loadProfile();
      await loadFeed();
    } catch (error) {
      setAuthError(getErrorMessage(error));
    } finally {
      setAuthBusy(false);
    }
  }

  function handleLogout() {
    logout();
    setProfile(null);
    setSelectedPostId(null);
    feedState.setData({ items: [], nextCursor: null, sourceVersionWatermark: null });
    searchState.setData([]);
    detailState.reset();
    commentsState.reset();
    setFlash({ tone: 'info', title: 'Signed out', message: 'The local JWT was removed from this browser.' });
  }

  async function handleCreatePost(values, form) {
    setComposerBusy(true);
    setFlash(null);
    try {
      await api('/posts', {
        method: 'POST',
        body: JSON.stringify({ content: values.content }),
      });
      form.reset();
      await loadFeed();
      setFlash({ tone: 'success', title: 'Post published', message: 'Your feed has been refreshed from the BFF.' });
    } catch (error) {
      setFlash({ tone: 'danger', title: 'Could not publish post', message: getErrorMessage(error) });
    } finally {
      setComposerBusy(false);
    }
  }

  async function handleSearch(term) {
    const query = term.trim();
    if (!query) {
      searchState.setData([]);
      return;
    }
    setFlash(null);
    try {
      await searchState.run(async () => normalizeSearch(await api(`/post-search?q=${encodeURIComponent(query)}`)));
    } catch (error) {
      setFlash({ tone: 'danger', title: 'Search failed', message: getErrorMessage(error) });
    }
  }

  async function handleCommentSubmit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const values = Object.fromEntries(new FormData(form));
    if (!selectedPostId) return;
    setCommentBusy(true);
    setFlash(null);
    try {
      await ensureCommentTargetReady(selectedPostId);
      await api(`/comments/targets/post/${selectedPostId}`, {
        method: 'POST',
        body: JSON.stringify({ content: values.content }),
      });
      form.reset();
      await refreshFeedAndDetail(selectedPostId);
      setFlash({ tone: 'success', title: 'Comment posted', message: 'Discussion is now up to date.' });
    } catch (error) {
      setFlash({ tone: 'danger', title: 'Could not post comment', message: getErrorMessage(error) });
    } finally {
      setCommentBusy(false);
    }
  }

  const sidebar = (
    <div className="fb-sidebar-stack">
      <Card className="fb-profile-card">
        <div className="fb-profile-card__hero" />
        <div className="fb-profile-card__body">
          <Avatar name={getProfileName(profile)} size="lg" />
          <h2>{getProfileName(profile)}</h2>
          <p>@{profile?.username || 'guest'}</p>
          <div className="fb-role-row">
            {getRoles(profile).length
              ? getRoles(profile).map(role => <Badge key={role} tone="success">{role}</Badge>)
              : <Badge>Member</Badge>}
          </div>
        </div>
      </Card>
      <DisabledFeatureCard
        title="Stories"
        description="Short-lived story playback needs a backend contract that is not available in this workspace."
      />
      <DisabledFeatureCard
        title="Reactions"
        description="Like and reaction counters are intentionally visible as a future surface."
      />
    </div>
  );

  if (!loggedIn) {
    return (
      <AppShell className="fb-auth-shell">
        <div className="fb-auth-layout">
          <section className="fb-auth-copy">
            <Badge tone="success">React + Kong gateway</Badge>
            <h1>Neighborhood</h1>
            <p>
              A compact Facebook-style client wired to the real pluggable Java backends:
              auth for identity, BFF for feed reads, tweeter for posting, comments for discussion,
              and post-search for discovery.
            </p>
            {flash ? <StatusNotice tone={flash.tone} title={flash.title}>{flash.message}</StatusNotice> : null}
            <Card className="fb-auth-switch">
              <Button variant={authMode === 'login' ? 'primary' : 'secondary'} onClick={() => setAuthMode('login')}>Login</Button>
              <Button variant={authMode === 'register' ? 'primary' : 'secondary'} onClick={() => setAuthMode('register')}>Register</Button>
            </Card>
          </section>
          <AuthForm mode={authMode} busy={authBusy} error={authError} onSubmit={handleAuthSubmit} />
        </div>
      </AppShell>
    );
  }

  const feed = feedState.data || { items: [] };
  const searchResults = searchState.data || [];
  const selectedPost = detailState.data;

  return (
    <AppShell sidebar={sidebar} className="fb-shell">
      <PageHeader
        eyebrow="Facebook-style social client"
        title="Neighborhood Feed"
        description="Reads come through the BFF. Writes go directly to the owning services."
        actions={(
          <div className="fb-header-actions">
            <Badge tone="success">Gateway: {GATEWAY}</Badge>
            <Button variant="secondary" onClick={handleLogout}>Logout</Button>
          </div>
        )}
      />
      {flash ? <StatusNotice tone={flash.tone} title={flash.title} onDismiss={() => setFlash(null)}>{flash.message}</StatusNotice> : null}
      <div className="fb-layout">
        <section className="fb-main-column">
          <Composer
            title="Share something with your neighborhood"
            label="Post"
            maxLength={280}
            busy={composerBusy}
            onSubmit={handleCreatePost}
          />
          <Card className="fb-search-card">
            <CardHeader title="Search public moments" description="Queries the asynchronous post-search projection." />
            <SearchBox
              value={searchTerm}
              onChange={setSearchTerm}
              onSubmit={handleSearch}
              busy={searchState.loading}
              placeholder="Search by keyword"
            />
            {searchTerm.trim() ? (
              <AsyncBoundary
                loading={searchState.loading}
                error={searchState.error}
                empty={!searchResults.length}
                emptyTitle="No matching posts"
                emptyDescription="Try a different query after the search projection catches up."
              >
                <div className="fb-search-results">
                  {searchResults.map((item, index) => (
                    <Card key={getSearchItemKey(item, index)} className="fb-search-result-card">
                      <strong>{getSearchTitle(item)}</strong>
                      <p>{getSearchDescription(item)}</p>
                    </Card>
                  ))}
                </div>
              </AsyncBoundary>
            ) : null}
          </Card>
          <AsyncBoundary
            loading={feedState.loading}
            error={feedState.error}
            empty={!feed.items.length}
            emptyTitle="Your feed is quiet"
            emptyDescription="Create the first post and it will appear here through the BFF."
          >
            <div className="fb-feed-list">
              {feed.items.map(item => (
                <FeedPostCard key={item?.post?.id || item?.post?.createdAt} item={item} onOpen={openPost} />
              ))}
            </div>
          </AsyncBoundary>
        </section>
        <section className="fb-right-rail">
          <Card className="fb-insight-card">
            <CardHeader title="Feed health" description="Rendered defensively against partial BFF composition." />
            <div className="fb-insight-grid">
              <div><strong>{feed.items.length}</strong><span>Posts loaded</span></div>
              <div><strong>{feed.sourceVersionWatermark ?? 'n/a'}</strong><span>Watermark</span></div>
            </div>
          </Card>
          <DisabledFeatureCard
            title="Messenger rooms"
            description="Realtime room orchestration is not exposed by the available chat endpoints."
          />
        </section>
      </div>
      <Modal
        open={Boolean(selectedPostId)}
        title={selectedPost?.author?.username ? `Discussion with ${selectedPost.author.username}` : 'Post discussion'}
        onClose={closePost}
        footer={(
          <Button variant="secondary" onClick={closePost}>
            Close
          </Button>
        )}
      >
        <AsyncBoundary
          loading={detailState.loading || commentsState.loading}
          error={detailState.error || commentsState.error}
          empty={!selectedPost}
          emptyTitle="Post not available"
          emptyDescription="This post may have been deleted."
        >
          <div className="fb-modal-stack">
            <FeedPostCard item={selectedPost} onOpen={() => {}} />
            <Card as="form" className="ab-stack" onSubmit={handleCommentSubmit}>
              <CardHeader title="Join the conversation" description="Comments are written directly to the comment service." />
              <textarea className="ab-input ab-textarea" name="content" maxLength={280} required placeholder="Write a thoughtful reply" />
              <div className="ab-actions">
                <Button type="submit" busy={commentBusy}>Comment</Button>
              </div>
            </Card>
            <CommentList comments={commentsState.data || []} />
          </div>
        </AsyncBoundary>
      </Modal>
    </AppShell>
  );
}
