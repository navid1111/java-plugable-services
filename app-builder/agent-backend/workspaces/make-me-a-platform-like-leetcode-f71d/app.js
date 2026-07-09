const GATEWAY = "http://localhost:18000";
const TOKEN_KEY = "appbuilder.jwt";

const state = {
  user: null,
  posts: [],
  selectedPostId: null,
};

const els = {
  authDot: document.querySelector("#authDot"),
  authStatus: document.querySelector("#authStatus"),
  authDetail: document.querySelector("#authDetail"),
  message: document.querySelector("#message"),
  loginForm: document.querySelector("#loginForm"),
  registerForm: document.querySelector("#registerForm"),
  logoutBtn: document.querySelector("#logoutBtn"),
  refreshFeedBtn: document.querySelector("#refreshFeedBtn"),
  postForm: document.querySelector("#postForm"),
  postContent: document.querySelector("#postContent"),
  searchInput: document.querySelector("#searchInput"),
  searchBtn: document.querySelector("#searchBtn"),
  feedList: document.querySelector("#feedList"),
  commentsTitle: document.querySelector("#commentsTitle"),
  commentList: document.querySelector("#commentList"),
  commentForm: document.querySelector("#commentForm"),
  commentContent: document.querySelector("#commentContent"),
};

function token() {
  return localStorage.getItem(TOKEN_KEY);
}

async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (!(options.body instanceof FormData)) {
    headers["Content-Type"] = headers["Content-Type"] || "application/json";
  }

  const jwt = token();
  if (jwt) headers.Authorization = `Bearer ${jwt}`;

  let res;
  try {
    res = await fetch(GATEWAY + path, { ...options, headers });
  } catch (error) {
    throw new Error(`Gateway request failed. Check Kong/CORS at ${GATEWAY}. ${error.message}`);
  }

  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
  return readJson(res);
}

async function login(username, password) {
  let res;
  try {
    res = await fetch(GATEWAY + "/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
  } catch (error) {
    throw new Error(`Gateway request failed. Check Kong/CORS at ${GATEWAY}. ${error.message}`);
  }

  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
  const data = await readJson(res);
  localStorage.setItem(TOKEN_KEY, data.access_token);
  return data;
}

async function register(username, password) {
  let res;
  try {
    res = await fetch(GATEWAY + "/auth/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
  } catch (error) {
    throw new Error(`Gateway request failed. Check Kong/CORS at ${GATEWAY}. ${error.message}`);
  }

  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
  return readJson(res);
}

async function readJson(res) {
  if (res.status === 204) return null;
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

function setMessage(text, type = "") {
  els.message.textContent = text;
  els.message.className = `message ${type}`.trim();
  els.message.classList.toggle("hidden", !text);
}

function clearMessage() {
  setMessage("");
}

function requireAuth() {
  if (token()) return true;
  setMessage("Login first. Content services reject anonymous requests by design.", "error");
  return false;
}

function getUserLabel() {
  if (!state.user) return "Authenticated user";
  return state.user.username || state.user.name || state.user.sub || state.user.email || "Authenticated user";
}

function setLoggedOut(message = "Login to use backend features.") {
  state.user = null;
  state.posts = [];
  state.selectedPostId = null;
  localStorage.removeItem(TOKEN_KEY);
  els.authDot.classList.remove("online");
  els.authStatus.textContent = "Signed out";
  els.authDetail.textContent = message;
  els.logoutBtn.classList.add("hidden");
  renderFeedNotice("Login to load discussions from /posts/feed.");
  renderCommentPlaceholder("Open a discussion from the feed to read or write comments.");
}

function setLoggedIn(user) {
  state.user = user || {};
  els.authDot.classList.add("online");
  els.authStatus.textContent = getUserLabel();
  els.authDetail.textContent = "JWT stored locally and attached to protected calls.";
  els.logoutBtn.classList.remove("hidden");
}

function normalizeList(data) {
  if (Array.isArray(data)) return data;
  if (!data || typeof data !== "object") return [];
  if (Array.isArray(data.content)) return data.content;
  if (Array.isArray(data.items)) return data.items;
  if (Array.isArray(data.results)) return data.results;
  if (Array.isArray(data.posts)) return data.posts;
  if (Array.isArray(data.documents)) return data.documents;
  return [];
}

function postId(post) {
  return post?.id ?? post?.postId ?? post?.targetId ?? post?.documentId ?? post?.target?.id;
}

function postAuthor(post) {
  return post?.authorUsername || post?.username || post?.author?.username || post?.createdBy || "unknown";
}

function postContent(post) {
  return post?.content || post?.text || post?.body || post?.document?.content || "";
}

function postCreated(post) {
  const value = post?.createdAt || post?.created_at || post?.timestamp;
  if (!value) return "";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}

function commentId(comment) {
  return comment?.id ?? comment?.commentId;
}

function renderFeedNotice(text) {
  els.feedList.innerHTML = "";
  const empty = document.createElement("p");
  empty.className = "empty";
  empty.textContent = text;
  els.feedList.append(empty);
}

function renderCommentPlaceholder(text) {
  els.commentsTitle.textContent = "Select a discussion";
  els.commentList.innerHTML = "";
  const empty = document.createElement("p");
  empty.className = "empty";
  empty.textContent = text;
  els.commentList.append(empty);
  els.commentForm.classList.add("hidden");
}

function renderPosts(posts, mode = "feed") {
  state.posts = posts;
  els.feedList.innerHTML = "";

  if (!posts.length) {
    renderFeedNotice(mode === "search" ? "No matching discussions returned by /post-search." : "No discussions yet.");
    return;
  }

  const fragment = document.createDocumentFragment();
  posts.forEach((post) => {
    const id = postId(post);
    const card = document.createElement("article");
    card.className = "post-card";

    const meta = document.createElement("div");
    meta.className = "post-meta";
    meta.append(metaItem(mode === "search" ? "Search result" : "Discussion"));
    meta.append(metaItem(`@${postAuthor(post)}`));
    const created = postCreated(post);
    if (created) meta.append(metaItem(created));
    if (id) meta.append(metaItem(`#${id}`));

    const body = document.createElement("p");
    body.className = "post-body";
    body.textContent = postContent(post) || "No content returned for this discussion.";

    const actions = document.createElement("div");
    actions.className = "post-actions";
    const openBtn = document.createElement("button");
    openBtn.className = "ghost";
    openBtn.type = "button";
    openBtn.textContent = "Open comments";
    openBtn.disabled = !id;
    openBtn.addEventListener("click", () => selectPost(id, post));
    actions.append(openBtn);

    card.append(meta, body, actions);
    fragment.append(card);
  });

  els.feedList.append(fragment);
}

function metaItem(text) {
  const item = document.createElement("span");
  item.textContent = text;
  return item;
}

function renderComments(comments) {
  els.commentList.innerHTML = "";

  if (!comments.length) {
    const empty = document.createElement("p");
    empty.className = "empty";
    empty.textContent = "No comments yet for this discussion.";
    els.commentList.append(empty);
    return;
  }

  const fragment = document.createDocumentFragment();
  comments.forEach((comment) => {
    const card = document.createElement("article");
    card.className = "comment-card";

    const meta = document.createElement("div");
    meta.className = "comment-meta";
    meta.append(metaItem(`@${postAuthor(comment)}`));
    const created = postCreated(comment);
    if (created) meta.append(metaItem(created));
    const id = commentId(comment);
    if (id) meta.append(metaItem(`#${id}`));

    const body = document.createElement("p");
    body.className = "comment-body";
    body.textContent = postContent(comment) || "No content returned for this comment.";

    card.append(meta, body);
    fragment.append(card);
  });

  els.commentList.append(fragment);
}

async function loadCurrentUser() {
  const user = await api("/auth/me");
  setLoggedIn(user);
}

async function loadFeed(options = {}) {
  if (!requireAuth()) return;
  if (!options.preserveMessage) clearMessage();
  renderFeedNotice("Loading discussions from /posts/feed...");
  try {
    const data = await api("/posts/feed");
    renderPosts(normalizeList(data));
  } catch (error) {
    renderFeedNotice("Could not load the discussion feed.");
    setMessage(error.message, "error");
  }
}

async function createPost(event) {
  event.preventDefault();
  if (!requireAuth()) return;

  const content = els.postContent.value.trim();
  if (!content) return;

  try {
    const post = await api("/posts", {
      method: "POST",
      body: JSON.stringify({ content }),
    });

    const id = postId(post);
    if (id) {
      try {
        await api(`/post-search/documents/post/${encodeURIComponent(id)}`, {
          method: "PUT",
          body: JSON.stringify({
            authorUsername: getUserLabel(),
            content,
            createdAt: new Date().toISOString(),
          }),
        });
      } catch (indexError) {
        setMessage(`Discussion posted, but search indexing failed: ${indexError.message}`, "error");
      }
    }

    els.postContent.value = "";
    if (!els.message.classList.contains("error")) setMessage("Discussion posted.", "success");
    await loadFeed({ preserveMessage: true });
  } catch (error) {
    setMessage(error.message, "error");
  }
}

async function searchPosts() {
  if (!requireAuth()) return;

  const q = els.searchInput.value.trim();
  if (!q) {
    setMessage("Enter a search term. /post-search requires q.", "error");
    return;
  }

  clearMessage();
  renderFeedNotice("Searching discussions...");
  try {
    const data = await api(`/post-search?q=${encodeURIComponent(q)}`);
    renderPosts(normalizeList(data), "search");
  } catch (error) {
    renderFeedNotice("Search failed.");
    setMessage(error.message, "error");
  }
}

async function selectPost(id, post) {
  if (!requireAuth()) return;

  state.selectedPostId = id;
  els.commentsTitle.textContent = `Discussion #${id}`;
  els.commentForm.classList.remove("hidden");
  els.commentList.innerHTML = "";
  const loading = document.createElement("p");
  loading.className = "empty";
  loading.textContent = "Loading comments...";
  els.commentList.append(loading);

  try {
    const data = await api(`/comments/targets/post/${encodeURIComponent(id)}`);
    renderComments(normalizeList(data));
  } catch (error) {
    renderCommentPlaceholder(`Could not load comments for "${postContent(post).slice(0, 60)}".`);
    setMessage(error.message, "error");
  }
}

async function createComment(event) {
  event.preventDefault();
  if (!requireAuth() || !state.selectedPostId) return;

  const content = els.commentContent.value.trim();
  if (!content) return;

  try {
    await api(`/comments/targets/post/${encodeURIComponent(state.selectedPostId)}`, {
      method: "POST",
      body: JSON.stringify({ content }),
    });
    els.commentContent.value = "";
    setMessage("Comment added.", "success");
    await selectPost(state.selectedPostId, { id: state.selectedPostId });
  } catch (error) {
    setMessage(error.message, "error");
  }
}

async function handleLogin(event) {
  event.preventDefault();
  const form = new FormData(event.currentTarget);
  try {
    await login(String(form.get("username")).trim(), String(form.get("password")));
    await loadCurrentUser();
    setMessage("Logged in.", "success");
    await loadFeed();
  } catch (error) {
    setMessage(error.message, "error");
  }
}

async function handleRegister(event) {
  event.preventDefault();
  const form = new FormData(event.currentTarget);
  const username = String(form.get("username")).trim();
  const password = String(form.get("password"));

  try {
    await register(username, password);
    await login(username, password);
    await loadCurrentUser();
    setMessage("Account created and logged in.", "success");
    event.currentTarget.reset();
    await loadFeed();
  } catch (error) {
    setMessage(error.message, "error");
  }
}

function logout() {
  setLoggedOut("JWT removed from local storage.");
  setMessage("Logged out.", "success");
}

async function boot() {
  els.loginForm.addEventListener("submit", handleLogin);
  els.registerForm.addEventListener("submit", handleRegister);
  els.logoutBtn.addEventListener("click", logout);
  els.refreshFeedBtn.addEventListener("click", loadFeed);
  els.postForm.addEventListener("submit", createPost);
  els.searchBtn.addEventListener("click", searchPosts);
  els.searchInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      searchPosts();
    }
  });
  els.commentForm.addEventListener("submit", createComment);

  if (!token()) {
    setLoggedOut();
    return;
  }

  try {
    await loadCurrentUser();
    await loadFeed();
  } catch (error) {
    setLoggedOut("Stored JWT was rejected. Login again.");
    setMessage(error.message, "error");
  }
}

boot();
