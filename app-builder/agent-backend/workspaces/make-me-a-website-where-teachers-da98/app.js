const GATEWAY = "http://localhost:18000";
const JWT_KEY = "appbuilder.jwt";

const state = {
  me: null,
  feed: [],
  resources: [],
  bookings: [],
  searchItems: []
};

async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (!(options.body instanceof FormData)) {
    headers["Content-Type"] = headers["Content-Type"] || "application/json";
  }
  const token = localStorage.getItem(JWT_KEY);
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(GATEWAY + path, { ...options, headers });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`${res.status} ${text || res.statusText}`);
  }
  return res.status === 204 ? null : res.json();
}

async function login(username, password) {
  const res = await fetch(GATEWAY + "/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password })
  });
  if (!res.ok) {
    throw new Error(`${res.status} ${await res.text()}`);
  }
  const data = await res.json();
  localStorage.setItem(JWT_KEY, data.access_token);
}

function $(id) {
  return document.getElementById(id);
}

function setNotice(id, message, type = "error") {
  const el = $(id);
  if (!message) {
    el.textContent = "";
    el.classList.add("hidden");
    return;
  }
  el.textContent = message;
  el.classList.remove("hidden");
  el.classList.toggle("notice-error", type === "error");
  el.classList.toggle("notice-success", type === "success");
}

function clearNotices() {
  ["authError", "composerError", "composerSuccess", "searchError", "feedError", "bookingError", "bookingSuccess"].forEach((id) => {
    setNotice(id, "");
  });
}

function formatDate(value) {
  if (!value) return "No schedule provided";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function asArray(value) {
  return Array.isArray(value) ? value : [];
}

function summarizeResource(resource) {
  const preferredKeys = ["title", "name", "subject", "description", "resourceType", "category"];
  for (const key of preferredKeys) {
    if (resource && resource[key]) return String(resource[key]);
  }
  return `Resource #${resource?.id ?? resource?.resourceId ?? "unknown"}`;
}

function getResourceId(resource) {
  return resource?.id ?? resource?.resourceId ?? resource?.bookingResourceId ?? null;
}

function renderFeed() {
  const el = $("feedList");
  if (!localStorage.getItem(JWT_KEY)) {
    el.className = "card-list empty-state";
    el.textContent = "Sign in to load classes.";
    return;
  }
  if (!state.feed.length) {
    el.className = "card-list empty-state";
    el.textContent = "No classes published yet.";
    return;
  }
  el.className = "card-list";
  el.innerHTML = state.feed.map((item) => {
    const degraded = asArray(item.degraded);
    return `
      <article class="listing-card">
        <div class="card-topline">
          <div>
            <h3>${escapeHtml(item.author?.username || "Teacher")}</h3>
            <p class="meta">${escapeHtml(formatDate(item.post?.createdAt))}</p>
          </div>
          <div class="tag-row">
            <span class="tag">${item.comments?.commentCount ?? 0} comments</span>
            <span class="tag tag-muted">${item.media?.mediaCount ?? 0} media</span>
          </div>
        </div>
        <p>${escapeHtml(item.post?.content || "")}</p>
        ${degraded.length ? `<p class="meta">Temporarily unavailable: ${escapeHtml(degraded.join(", "))}</p>` : ""}
      </article>
    `;
  }).join("");
}

function renderSearch() {
  const el = $("searchResults");
  if (!localStorage.getItem(JWT_KEY)) {
    el.className = "card-list empty-state";
    el.textContent = "Sign in to search published classes.";
    return;
  }
  if (!state.searchItems.length) {
    el.className = "card-list empty-state";
    el.textContent = "No search results yet.";
    return;
  }
  el.className = "card-list";
  el.innerHTML = state.searchItems.map((item) => `
    <article class="result-card">
      <div class="card-topline">
        <h3>${escapeHtml(item.title || item.targetType || "Class listing")}</h3>
        <span class="tag">Post #${escapeHtml(item.targetId)}</span>
      </div>
      <p>${escapeHtml(item.snippet || item.content || "Indexed class listing")}</p>
    </article>
  `).join("");
}

function renderResources() {
  const el = $("resourceList");
  if (!localStorage.getItem(JWT_KEY)) {
    el.className = "card-list empty-state";
    el.textContent = "Sign in to view bookable resources.";
    return;
  }
  if (!state.resources.length) {
    el.className = "card-list empty-state";
    el.textContent = "No booking resources are available right now.";
    return;
  }
  el.className = "card-list";
  el.innerHTML = state.resources.map((resource) => {
    const resourceId = getResourceId(resource);
    return `
      <article class="resource-card">
        <div class="card-topline">
          <div>
            <h3>${escapeHtml(summarizeResource(resource))}</h3>
            <p class="meta">Resource ID: ${escapeHtml(resourceId)}</p>
          </div>
          <div class="tag-row">
            ${Object.entries(resource)
              .filter(([key, value]) => value && ["subject", "level", "capacity", "location", "startsAt"].includes(key))
              .map(([key, value]) => `<span class="tag ${key === "startsAt" ? "tag-muted" : ""}">${escapeHtml(key)}: ${escapeHtml(key === "startsAt" ? formatDate(value) : value)}</span>`)
              .join("")}
          </div>
        </div>
        <pre class="meta">${escapeHtml(JSON.stringify(resource, null, 2))}</pre>
        <div class="card-actions">
          <button class="button button-primary" type="button" data-book-resource="${escapeHtml(resourceId)}">Book this class</button>
        </div>
      </article>
    `;
  }).join("");
}

function renderBookings() {
  const el = $("myBookings");
  if (!localStorage.getItem(JWT_KEY)) {
    el.className = "card-list empty-state";
    el.textContent = "Your reservations will appear here.";
    return;
  }
  if (!state.bookings.length) {
    el.className = "card-list empty-state";
    el.textContent = "No bookings yet.";
    return;
  }
  el.className = "card-list";
  el.innerHTML = state.bookings.map((booking) => {
    const bookingId = booking?.id ?? booking?.bookingId ?? "unknown";
    return `
      <article class="booking-card">
        <div class="card-topline">
          <div>
            <h3>${escapeHtml(booking.title || summarizeResource(booking))}</h3>
            <p class="meta">Booking ID: ${escapeHtml(bookingId)}</p>
          </div>
          <span class="tag tag-muted">${escapeHtml(formatDate(booking.startsAt || booking.createdAt))}</span>
        </div>
        <pre class="meta">${escapeHtml(JSON.stringify(booking, null, 2))}</pre>
        <div class="card-actions">
          <button class="button button-ghost" type="button" data-cancel-booking="${escapeHtml(bookingId)}">Cancel</button>
        </div>
      </article>
    `;
  }).join("");
}

function updateIdentity() {
  $("identityText").textContent = state.me?.username || "Guest";
  $("modeText").textContent = state.me ? "Publish and book" : "Browse classes";
  $("logoutBtn").classList.toggle("hidden", !state.me);
}

async function loadSession() {
  if (!localStorage.getItem(JWT_KEY)) {
    state.me = null;
    state.feed = [];
    state.resources = [];
    state.bookings = [];
    state.searchItems = [];
    updateIdentity();
    renderFeed();
    renderResources();
    renderBookings();
    renderSearch();
    return;
  }
  try {
    state.me = await api("/auth/me");
  } catch (error) {
    localStorage.removeItem(JWT_KEY);
    state.me = null;
    setNotice("authError", `Session check failed: ${error.message}`);
  }
  updateIdentity();
}

async function refreshFeed() {
  if (!localStorage.getItem(JWT_KEY)) return renderFeed();
  try {
    const data = await api("/bff/feed");
    state.feed = asArray(data.items);
    setNotice("feedError", "");
  } catch (error) {
    state.feed = [];
    setNotice("feedError", `Could not load classes: ${error.message}`);
  }
  renderFeed();
}

async function refreshBookings() {
  if (!localStorage.getItem(JWT_KEY)) {
    renderResources();
    renderBookings();
    return;
  }
  try {
    const [resources, bookings] = await Promise.all([
      api("/bookings/resources"),
      api("/bookings/mine")
    ]);
    state.resources = asArray(resources.items || resources);
    state.bookings = asArray(bookings.items || bookings);
    setNotice("bookingError", "");
  } catch (error) {
    state.resources = [];
    state.bookings = [];
    setNotice("bookingError", `Booking service error: ${error.message}`);
  }
  renderResources();
  renderBookings();
}

async function refreshAll() {
  clearNotices();
  await loadSession();
  if (!state.me) return;
  await Promise.all([refreshFeed(), refreshBookings()]);
}

async function handleRegister(event) {
  event.preventDefault();
  setNotice("authError", "");
  const form = new FormData(event.currentTarget);
  const username = String(form.get("username") || "").trim();
  const password = String(form.get("password") || "");
  try {
    await api("/auth/register", {
      method: "POST",
      body: JSON.stringify({ username, password })
    });
    await login(username, password);
    event.currentTarget.reset();
    $("loginForm").reset();
    await refreshAll();
  } catch (error) {
    setNotice("authError", `Registration failed: ${error.message}`);
  }
}

async function handleLogin(event) {
  event.preventDefault();
  setNotice("authError", "");
  const form = new FormData(event.currentTarget);
  const username = String(form.get("username") || "").trim();
  const password = String(form.get("password") || "");
  try {
    await login(username, password);
    event.currentTarget.reset();
    await refreshAll();
  } catch (error) {
    setNotice("authError", `Login failed: ${error.message}`);
  }
}

async function handlePost(event) {
  event.preventDefault();
  setNotice("composerError", "");
  setNotice("composerSuccess", "");
  if (!state.me) {
    setNotice("composerError", "Sign in before publishing a class.");
    return;
  }
  const form = new FormData(event.currentTarget);
  const content = String(form.get("content") || "").trim();
  try {
    await api("/posts", {
      method: "POST",
      body: JSON.stringify({ content })
    });
    event.currentTarget.reset();
    setNotice("composerSuccess", "Class published.");
    await refreshFeed();
  } catch (error) {
    setNotice("composerError", `Could not publish class: ${error.message}`);
  }
}

async function handleSearch(event) {
  event.preventDefault();
  setNotice("searchError", "");
  if (!state.me) {
    setNotice("searchError", "Sign in before searching class listings.");
    return;
  }
  const query = String(new FormData(event.currentTarget).get("query") || "").trim();
  try {
    const data = await api(`/post-search?q=${encodeURIComponent(query)}`);
    state.searchItems = asArray(data.items || data);
  } catch (error) {
    state.searchItems = [];
    setNotice("searchError", `Search failed: ${error.message}`);
  }
  renderSearch();
}

async function handleBook(resourceId) {
  setNotice("bookingError", "");
  setNotice("bookingSuccess", "");
  if (!state.me) {
    setNotice("bookingError", "Sign in before booking a class.");
    return;
  }
  if (!resourceId) {
    setNotice("bookingError", "This resource does not expose a usable id.");
    return;
  }
  try {
    await api("/bookings", {
      method: "POST",
      body: JSON.stringify({ resourceId })
    });
    setNotice("bookingSuccess", `Booked resource ${resourceId}.`);
    await refreshBookings();
  } catch (error) {
    setNotice("bookingError", `Could not create booking: ${error.message}`);
  }
}

async function handleCancel(bookingId) {
  setNotice("bookingError", "");
  setNotice("bookingSuccess", "");
  try {
    await api(`/bookings/${encodeURIComponent(bookingId)}`, { method: "DELETE" });
    setNotice("bookingSuccess", `Canceled booking ${bookingId}.`);
    await refreshBookings();
  } catch (error) {
    setNotice("bookingError", `Could not cancel booking: ${error.message}`);
  }
}

function bindEvents() {
  $("registerForm").addEventListener("submit", handleRegister);
  $("loginForm").addEventListener("submit", handleLogin);
  $("postForm").addEventListener("submit", handlePost);
  $("searchForm").addEventListener("submit", handleSearch);
  $("refreshAllBtn").addEventListener("click", refreshAll);
  $("logoutBtn").addEventListener("click", async () => {
    localStorage.removeItem(JWT_KEY);
    state.me = null;
    await refreshAll();
  });
  document.body.addEventListener("click", (event) => {
    const bookButton = event.target.closest("[data-book-resource]");
    if (bookButton) {
      handleBook(bookButton.getAttribute("data-book-resource"));
      return;
    }
    const cancelButton = event.target.closest("[data-cancel-booking]");
    if (cancelButton) {
      handleCancel(cancelButton.getAttribute("data-cancel-booking"));
    }
  });
}

bindEvents();
updateIdentity();
renderFeed();
renderResources();
renderBookings();
renderSearch();
refreshAll();
