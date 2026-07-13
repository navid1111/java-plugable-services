import { useEffect, useMemo, useState } from 'react';
import {
  AppShell,
  Avatar,
  Badge,
  Button,
  Card,
  CardHeader,
  EmptyState,
  Field,
  Modal,
  PageHeader,
  StatusNotice,
  TextArea,
  TextInput,
} from './components/AppBuilderUI.jsx';
import { AsyncBoundary, AuthForm, SearchBox } from './components/AppBuilderPatterns.jsx';
import { ApiError, api, getToken, logout as clearToken, setToken } from './lib/api.js';

const MEDIA_RETRY_DELAYS_MS = [250, 600, 1200];

function formatRelative(dateString) {
  if (!dateString) return 'Unknown';
  const date = new Date(dateString);
  const diffMs = Date.now() - date.getTime();
  const minutes = Math.round(diffMs / 60000);
  if (minutes < 1) return 'Just now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  if (days < 30) return `${days}d ago`;
  return date.toLocaleDateString();
}

function guessMediaUrl(item) {
  return item?.secureUrl || item?.url || item?.playbackUrl || item?.src || '';
}

function guessMediaKind(item) {
  const value = `${item?.resourceType || item?.type || item?.mimeType || item?.format || ''}`.toLowerCase();
  if (value.includes('video') || ['mp4', 'mov', 'webm'].includes(value)) return 'video';
  if (value.includes('image') || ['jpg', 'jpeg', 'png', 'webp', 'gif'].includes(value)) return 'image';
  return 'unknown';
}

function listToArray(payload) {
  if (Array.isArray(payload)) return payload;
  if (Array.isArray(payload?.items)) return payload.items;
  if (Array.isArray(payload?.results)) return payload.results;
  if (Array.isArray(payload?.documents)) return payload.documents;
  return [];
}

async function loadViewer() {
  return api('/auth/me');
}

async function login(username, password) {
  const response = await fetch('http://localhost:18000/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  const contentType = response.headers.get('content-type') || '';
  const payload = response.status === 204 ? null : contentType.includes('application/json')
    ? await response.json()
    : await response.text();
  if (!response.ok) {
    const message = typeof payload === 'string' ? payload : payload?.error || payload?.message || 'Login failed';
    throw new ApiError(response.status, message, payload);
  }
  setToken(payload?.access_token);
  return payload;
}

async function register(username, password) {
  await api('/auth/register', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  });
  return login(username, password);
}

async function loadFeed() {
  const payload = await api('/bff/feed');
  return payload?.items || [];
}

async function loadPostDetail(postId) {
  const [detail, media, comments] = await Promise.all([
    api(`/bff/posts/${postId}`),
    api(`/media/targets/post/${postId}`),
    api(`/comments/targets/post/${postId}`),
  ]);
  return {
    detail,
    media: listToArray(media),
    comments: listToArray(comments),
  };
}

async function loadSearchResults(query) {
  if (!query.trim()) return [];
  const payload = await api(`/post-search?q=${encodeURIComponent(query.trim())}`);
  return listToArray(payload);
}

async function waitForMediaTarget(postId) {
  for (const delay of MEDIA_RETRY_DELAYS_MS) {
    try {
      await api(`/media/targets/post/${postId}/summary`);
      return;
    } catch (error) {
      const message = String(error?.message || '').toLowerCase();
      const shouldRetry = error instanceof ApiError
        && error.status >= 400
        && error.status < 500
        && message.includes('target does not exist or is deleted');
      if (!shouldRetry) throw error;
      await new Promise(resolve => window.setTimeout(resolve, delay));
    }
  }
}

async function createVideoPost({ content, file }) {
  const post = await api('/posts', {
    method: 'POST',
    body: JSON.stringify({ content }),
  });
  if (file) {
    await waitForMediaTarget(post.id);
    const formData = new FormData();
    formData.append('file', file);
    await api(`/media/targets/post/${post.id}`, {
      method: 'POST',
      body: formData,
    });
  }
  return post;
}

async function createComment(postId, content) {
  return api(`/comments/targets/post/${postId}`, {
    method: 'POST',
    body: JSON.stringify({ content }),
  });
}

function VideoCard({ item, active, onSelect }) {
  const mediaCount = item?.media?.mediaCount ?? 0;
  const commentCount = item?.comments?.commentCount ?? 0;
  return (
    <button type="button" className={`video-card ${active ? 'video-card--active' : ''}`} onClick={() => onSelect(item)}>
      <div className="video-card__thumb">
        <span className="video-card__play">▶</span>
        <Badge tone="warning">{mediaCount ? `${mediaCount} media` : 'Text-only'}</Badge>
      </div>
      <div className="video-card__body">
        <Avatar name={item?.author?.username || 'Creator'} />
        <div>
          <h3>{item?.post?.content || 'Untitled upload'}</h3>
          <p>@{item?.author?.username || 'unknown'} · {formatRelative(item?.post?.createdAt)}</p>
          <p>{commentCount} comments</p>
        </div>
      </div>
    </button>
  );
}

function SearchResultCard({ result, onOpen }) {
  const title = result?.content || result?.title || result?.post?.content || `Post ${result?.targetId || result?.id}`;
  const postId = result?.targetId || result?.id || result?.postId || result?.post?.id;
  return (
    <Card className="search-result">
      <div className="search-result__meta">
        <Badge>{result?.targetType || 'post'}</Badge>
        <span>{result?.authorUsername ? `@${result.authorUsername}` : 'Indexed result'}</span>
      </div>
      <h3>{title}</h3>
      <Button variant="secondary" size="sm" disabled={!postId} onClick={() => postId && onOpen(postId)}>
        Open video
      </Button>
    </Card>
  );
}

function VideoPlayer({ mediaItems }) {
  const video = mediaItems.find(item => guessMediaKind(item) === 'video' && guessMediaUrl(item));
  const image = mediaItems.find(item => guessMediaKind(item) === 'image' && guessMediaUrl(item));
  if (video) {
    return (
      <div className="player-frame">
        <video controls preload="metadata" src={guessMediaUrl(video)} className="player-frame__video" />
      </div>
    );
  }
  if (image) {
    return (
      <div className="player-frame">
        <img src={guessMediaUrl(image)} alt="Video cover" className="player-frame__image" />
      </div>
    );
  }
  return (
    <div className="player-frame player-frame--empty">
      <div>
        <strong>Media temporarily unavailable</strong>
        <p>This post exists, but there is no playable attachment on the media service yet.</p>
      </div>
    </div>
  );
}

function Sidebar({ viewer, onLogout, onCompose }) {
  return (
    <div className="sidebar">
      <div className="brand-mark">
        <div className="brand-mark__logo">▶</div>
        <div>
          <strong>PulseTube</strong>
          <p>Gateway-backed video feed</p>
        </div>
      </div>

      <Card className="sidebar__card">
        <div className="sidebar__profile">
          <Avatar name={viewer?.username || 'Viewer'} size="lg" />
          <div>
            <h3>@{viewer?.username || 'viewer'}</h3>
            <p>{Array.isArray(viewer?.roles) ? viewer.roles.join(', ') : 'Authenticated user'}</p>
          </div>
        </div>
        <Button onClick={onCompose}>Upload a clip</Button>
        <Button variant="secondary" onClick={onLogout}>Log out</Button>
      </Card>

      <Card className="sidebar__card">
        <CardHeader title="Channels" description="Being developed — backend not available yet" />
        <Button variant="secondary" disabled>Subscriptions</Button>
      </Card>

      <Card className="sidebar__card">
        <CardHeader title="Playlists" description="Being developed — backend not available yet" />
        <Button variant="secondary" disabled>Watch later</Button>
      </Card>
    </div>
  );
}

function UploadModal({ open, busy, error, onClose, onSubmit }) {
  async function submit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const formData = new FormData(form);
    await onSubmit({
      content: String(formData.get('content') || ''),
      file: formData.get('file') instanceof File && formData.get('file')?.size ? formData.get('file') : null,
    }, form);
  }

  return (
    <Modal
      open={open}
      title="Upload a new clip"
      onClose={onClose}
      footer={<Button type="submit" form="upload-form" busy={busy}>Publish</Button>}
    >
      <form id="upload-form" className="ab-stack" onSubmit={submit}>
        <Field label="Video title / description" error={error}>
          <TextArea name="content" maxLength={280} required />
        </Field>
        <Field label="Media file" hint="Attach mp4, mov, webm, or an image poster.">
          <TextInput name="file" type="file" accept="video/*,image/*" />
        </Field>
        <StatusNotice tone="warning" title="Projection-aware upload">
          Posts are created first, then media is attached after the target projection becomes available.
        </StatusNotice>
      </form>
    </Modal>
  );
}

export default function App() {
  const [viewer, setViewer] = useState(null);
  const [authMode, setAuthMode] = useState('login');
  const [authBusy, setAuthBusy] = useState(false);
  const [authError, setAuthError] = useState('');
  const [feed, setFeed] = useState([]);
  const [feedLoading, setFeedLoading] = useState(false);
  const [feedError, setFeedError] = useState(null);
  const [selectedId, setSelectedId] = useState(null);
  const [detail, setDetail] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState(null);
  const [commentBusy, setCommentBusy] = useState(false);
  const [commentError, setCommentError] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [searchBusy, setSearchBusy] = useState(false);
  const [searchError, setSearchError] = useState(null);
  const [searchResults, setSearchResults] = useState([]);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [uploadBusy, setUploadBusy] = useState(false);
  const [uploadError, setUploadError] = useState('');

  async function refreshFeed(nextSelectedId) {
    setFeedLoading(true);
    setFeedError(null);
    try {
      const items = await loadFeed();
      setFeed(items);
      const selected = nextSelectedId ?? selectedId ?? items[0]?.post?.id ?? null;
      setSelectedId(selected);
    } catch (error) {
      setFeedError(error);
    } finally {
      setFeedLoading(false);
    }
  }

  async function refreshDetail(postId) {
    if (!postId) return;
    setDetailLoading(true);
    setDetailError(null);
    try {
      const payload = await loadPostDetail(postId);
      setDetail(payload);
    } catch (error) {
      setDetailError(error);
    } finally {
      setDetailLoading(false);
    }
  }

  useEffect(() => {
    if (!getToken()) return;
    loadViewer()
      .then(setViewer)
      .catch(() => {
        clearToken();
        setViewer(null);
      });
  }, []);

  useEffect(() => {
    if (!viewer) return;
    refreshFeed();
  }, [viewer]);

  useEffect(() => {
    if (!viewer || !selectedId) return;
    refreshDetail(selectedId);
  }, [viewer, selectedId]);

  const selectedFeedItem = useMemo(
    () => feed.find(item => item?.post?.id === selectedId) || null,
    [feed, selectedId],
  );

  async function handleAuthSubmit(values) {
    setAuthBusy(true);
    setAuthError('');
    try {
      if (authMode === 'register') {
        await register(values.username, values.password);
      } else {
        await login(values.username, values.password);
      }
      const nextViewer = await loadViewer();
      setViewer(nextViewer);
    } catch (error) {
      setAuthError(String(error?.message || error));
    } finally {
      setAuthBusy(false);
    }
  }

  function handleLogout() {
    clearToken();
    setViewer(null);
    setFeed([]);
    setDetail(null);
    setSelectedId(null);
    setSearchResults([]);
    setSearchError(null);
  }

  async function handleSearchSubmit(query) {
    setSearchBusy(true);
    setSearchError(null);
    try {
      const results = await loadSearchResults(query);
      setSearchResults(results);
    } catch (error) {
      setSearchError(error);
      setSearchResults([]);
    } finally {
      setSearchBusy(false);
    }
  }

  async function handleCommentSubmit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const formData = new FormData(form);
    const content = String(formData.get('content') || '').trim();
    if (!content || !selectedId) return;
    setCommentBusy(true);
    setCommentError('');
    try {
      await createComment(selectedId, content);
      form.reset();
      await refreshDetail(selectedId);
      await refreshFeed(selectedId);
    } catch (error) {
      setCommentError(String(error?.message || error));
    } finally {
      setCommentBusy(false);
    }
  }

  async function handleUploadSubmit(values, form) {
    setUploadBusy(true);
    setUploadError('');
    try {
      const post = await createVideoPost(values);
      form.reset();
      setUploadOpen(false);
      await refreshFeed(post.id);
      await refreshDetail(post.id);
    } catch (error) {
      setUploadError(String(error?.message || error));
    } finally {
      setUploadBusy(false);
    }
  }

  async function openPostFromSearch(postId) {
    setSelectedId(Number(postId));
    setSearchResults([]);
    setSearchQuery('');
  }

  if (!viewer) {
    return (
      <AppShell className="auth-shell">
        <div className="auth-layout">
          <section className="auth-hero">
            <Badge tone="danger">Video app</Badge>
            <h1>A YouTube-style frontend wired to your Java plugs.</h1>
            <p>Feed reads use the BFF, playback assets come from media-service, discussion comes from comment-service, and search stays projection-safe through post-search.</p>
          </section>
          <div className="auth-panel">
            <AuthForm mode={authMode} busy={authBusy} error={authError} onSubmit={handleAuthSubmit} />
            <Button variant="ghost" onClick={() => setAuthMode(authMode === 'login' ? 'register' : 'login')}>
              {authMode === 'login' ? 'Need an account? Register' : 'Already registered? Sign in'}
            </Button>
          </div>
        </div>
      </AppShell>
    );
  }

  return (
    <AppShell
      sidebar={<Sidebar viewer={viewer} onLogout={handleLogout} onCompose={() => setUploadOpen(true)} />}
      className="youtube-shell"
    >
      <PageHeader
        eyebrow="PulseTube"
        title="Your home feed"
        description="A YouTube-style surface built on posts, media, comments, and search."
        actions={<Badge tone="success">{feed.length} videos loaded</Badge>}
      />

      <div className="topbar">
        <SearchBox
          value={searchQuery}
          onChange={setSearchQuery}
          onSubmit={handleSearchSubmit}
          placeholder="Search creators, titles, or indexed post text"
          busy={searchBusy}
        />
        <Button onClick={() => setUploadOpen(true)}>New upload</Button>
      </div>

      {searchError ? (
        <StatusNotice tone="danger" title="Search failed">
          {String(searchError.message || searchError)}
        </StatusNotice>
      ) : null}

      {searchResults.length ? (
        <section className="search-results">
          <CardHeader title="Search results" description={`${searchResults.length} indexed matches`} />
          <div className="search-results__grid">
            {searchResults.map((result, index) => (
              <SearchResultCard key={result?.id || result?.targetId || index} result={result} onOpen={openPostFromSearch} />
            ))}
          </div>
        </section>
      ) : null}

      <div className="watch-layout">
        <section className="watch-main">
          <AsyncBoundary
            loading={detailLoading}
            error={detailError}
            empty={!detail}
            emptyTitle="Pick a video"
            emptyDescription="Select an item from the feed to start watching."
          >
            {detail ? (
              <div className="watch-stack">
                <VideoPlayer mediaItems={detail.media} />
                <Card>
                  <div className="watch-title">
                    <div>
                      <h2>{detail.detail?.post?.content || 'Untitled video'}</h2>
                      <p>Published {formatRelative(detail.detail?.post?.createdAt)} by @{detail.detail?.author?.username || 'unknown'}</p>
                    </div>
                    <Avatar name={detail.detail?.author?.username || 'Creator'} size="lg" />
                  </div>

                  {detail.detail?.degraded?.length ? (
                    <StatusNotice tone="warning" title="Partial read from BFF">
                      {`Temporarily unavailable: ${detail.detail.degraded.join(', ')}`}
                    </StatusNotice>
                  ) : null}

                  <div className="metrics-row">
                    <Badge>{detail.media.length} attachments</Badge>
                    <Badge>{detail.comments.length} comments</Badge>
                    <Badge tone="success">post #{detail.detail?.post?.id}</Badge>
                  </div>
                </Card>

                <Card as="form" className="ab-stack" onSubmit={handleCommentSubmit}>
                  <CardHeader title="Comments" description="Discuss this upload through comment-service." />
                  <Field label="Add a comment" error={commentError}>
                    <TextArea name="content" maxLength={280} required />
                  </Field>
                  <div className="ab-actions">
                    <Button type="submit" busy={commentBusy}>Post comment</Button>
                  </div>
                  <div className="comment-list">
                    {detail.comments.length ? detail.comments.map((comment, index) => (
                      <article className="comment-card" key={comment?.id || index}>
                        <Avatar name={comment?.author?.username || comment?.username || 'Viewer'} />
                        <div>
                          <strong>@{comment?.author?.username || comment?.username || 'viewer'}</strong>
                          <p>{comment?.content || 'No comment text returned.'}</p>
                        </div>
                      </article>
                    )) : (
                      <EmptyState
                        icon="💬"
                        title="No comments yet"
                        description="Start the discussion on this video."
                      />
                    )}
                  </div>
                </Card>
              </div>
            ) : null}
          </AsyncBoundary>
        </section>

        <aside className="watch-feed">
          <CardHeader title="Up next" description="Read through the BFF feed" />
          <AsyncBoundary
            loading={feedLoading}
            error={feedError}
            empty={!feed.length}
            emptyTitle="No videos in the feed"
            emptyDescription="Create the first post to seed the channel."
          >
            <div className="video-list">
              {feed.map(item => (
                <VideoCard
                  key={item?.post?.id}
                  item={item}
                  active={item?.post?.id === selectedFeedItem?.post?.id}
                  onSelect={next => setSelectedId(next?.post?.id)}
                />
              ))}
            </div>
          </AsyncBoundary>
        </aside>
      </div>

      <UploadModal
        open={uploadOpen}
        busy={uploadBusy}
        error={uploadError}
        onClose={() => setUploadOpen(false)}
        onSubmit={handleUploadSubmit}
      />
    </AppShell>
  );
}
