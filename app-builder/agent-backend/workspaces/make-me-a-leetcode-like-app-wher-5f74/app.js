const GATEWAY = "http://localhost:18000";
const state = { authMode: "login", me: null, problems: [], selected: null, editing: false };

const $ = (selector) => document.querySelector(selector);
const els = {
  authScreen: $("#auth-screen"), app: $("#app"), authForm: $("#auth-form"), username: $("#username"),
  password: $("#password"), authSubmit: $("#auth-submit"), authMessage: $("#auth-message"), identity: $("#identity"),
  logout: $("#logout"), adminNav: $("#admin-nav"), solveView: $("#solve-view"), adminView: $("#admin-view"),
  problemList: $("#problem-list"), problemCount: $("#problem-count"), problemEmpty: $("#problem-empty"),
  problemDetail: $("#problem-detail"), problemDifficulty: $("#problem-difficulty"), problemTitle: $("#problem-title"),
  problemTags: $("#problem-tags"), problemDescription: $("#problem-description"), examples: $("#examples"),
  editProblem: $("#edit-problem"), language: $("#language"), code: $("#code"), submitCode: $("#submit-code"),
  judgeState: $("#judge-state"), judgeResult: $("#judge-result"), adminForm: $("#problem-admin-form"),
  adminFormTitle: $("#admin-form-title"), adminId: $("#admin-id"), adminTitle: $("#admin-title"),
  adminDifficulty: $("#admin-difficulty"), adminTags: $("#admin-tags"), adminDescription: $("#admin-description"),
  adminJs: $("#admin-js"), adminPython: $("#admin-python"), testCases: $("#test-cases"),
  addTest: $("#add-test"), newProblem: $("#new-problem"), adminMessage: $("#admin-message"), toast: $("#toast")
};

async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (!(options.body instanceof FormData)) headers["Content-Type"] = headers["Content-Type"] || "application/json";
  const token = localStorage.getItem("appbuilder.jwt");
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(GATEWAY + path, { ...options, headers });
  const body = response.status === 204 ? null : await response.text();
  if (!response.ok) {
    let message = body;
    try { message = JSON.parse(body).detail || JSON.parse(body).message || body; } catch { /* plain response */ }
    throw new Error(`${response.status} ${message || response.statusText}`);
  }
  return body ? JSON.parse(body) : null;
}

function isAdmin() { return state.me?.roles?.includes("ADMIN"); }
function sleep(ms) { return new Promise((resolve) => setTimeout(resolve, ms)); }
function asObject(value, fallback) {
  if (value && typeof value === "object") return value;
  try { return JSON.parse(value); } catch { return fallback; }
}
function toast(message, error = false) {
  els.toast.textContent = message; els.toast.classList.toggle("error", error); els.toast.hidden = false;
  clearTimeout(toast.timer); toast.timer = setTimeout(() => { els.toast.hidden = true; }, 3500);
}

async function login(username, password) {
  const response = await fetch(GATEWAY + "/auth/login", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ username, password })
  });
  if (!response.ok) throw new Error(`${response.status} ${await response.text()}`);
  const data = await response.json(); localStorage.setItem("appbuilder.jwt", data.access_token);
}

async function establishSession() {
  const token = localStorage.getItem("appbuilder.jwt");
  if (!token) { showSignedOut(); return; }
  try {
    state.me = await api("/auth/me");
    els.authScreen.hidden = true; els.app.hidden = false; els.logout.hidden = false;
    els.identity.textContent = `@${state.me.username}${isAdmin() ? " · Admin" : ""}`;
    els.adminNav.hidden = !isAdmin(); els.editProblem.hidden = !isAdmin();
    await loadProblems();
  } catch (error) {
    localStorage.removeItem("appbuilder.jwt"); showSignedOut(); toast("Your session expired. Please log in again.", true);
  }
}

function showSignedOut() {
  state.me = null; els.authScreen.hidden = false; els.app.hidden = true; els.logout.hidden = true;
  els.identity.textContent = "Signed out"; els.adminNav.hidden = true;
}

function switchView(view) {
  if (view === "admin" && !isAdmin()) return;
  els.solveView.hidden = view !== "solve"; els.adminView.hidden = view !== "admin";
  document.querySelectorAll("[data-view]").forEach((button) => button.classList.toggle("active", button.dataset.view === view));
}

async function loadProblems(selectId) {
  const data = await api("/leetcode/problems?limit=100");
  state.problems = data.items || []; els.problemCount.textContent = String(data.total ?? state.problems.length);
  renderProblemList();
  const next = selectId || state.selected?.id || state.problems[0]?.id;
  if (next) await selectProblem(next); else showNoProblems();
}

function renderProblemList() {
  els.problemList.replaceChildren();
  state.problems.forEach((problem, index) => {
    const button = document.createElement("button"); button.className = "problem-item";
    button.classList.toggle("active", problem.id === state.selected?.id);
    const number = document.createElement("span"); number.className = "problem-number"; number.textContent = String(index + 1).padStart(2, "0");
    const copy = document.createElement("span"); copy.innerHTML = `<strong></strong><small></small>`;
    copy.querySelector("strong").textContent = problem.title; copy.querySelector("small").textContent = problem.difficulty;
    button.append(number, copy); button.addEventListener("click", () => selectProblem(problem.id)); els.problemList.append(button);
  });
}

function showNoProblems() {
  state.selected = null; els.problemEmpty.hidden = false; els.problemDetail.hidden = true;
  els.code.value = ""; els.submitCode.disabled = true; els.judgeState.textContent = isAdmin() ? "Create the first problem in Admin Studio." : "No problems are available yet.";
}

async function selectProblem(id) {
  const problem = await api(`/leetcode/problems/${encodeURIComponent(id)}`); state.selected = problem;
  renderProblemList(); els.problemEmpty.hidden = true; els.problemDetail.hidden = false;
  els.problemDifficulty.textContent = problem.difficulty; els.problemTitle.textContent = problem.title;
  els.problemDescription.textContent = problem.description; renderTags(asObject(problem.tags, [])); renderExamples(problem.examples || []);
  const stubs = asObject(problem.codeStubs, {}), languages = Object.keys(stubs);
  els.language.replaceChildren(); languages.forEach((language) => {
    const option = document.createElement("option"); option.value = language; option.textContent = language; els.language.append(option);
  });
  els.code.value = stubs[languages[0]] || ""; els.submitCode.disabled = !languages.length;
  els.judgeState.textContent = languages.length ? "Ready to submit against the judge." : "This problem has no starter code.";
  els.judgeResult.hidden = true;
}

function renderTags(tags) {
  els.problemTags.replaceChildren(); (Array.isArray(tags) ? tags : []).forEach((tag) => {
    const span = document.createElement("span"); span.className = "tag"; span.textContent = tag; els.problemTags.append(span);
  });
}

function renderExamples(examples) {
  els.examples.replaceChildren();
  if (!examples.length) { els.examples.textContent = "No public examples. The judge will use hidden tests."; return; }
  examples.forEach((example, index) => {
    const card = document.createElement("div"); card.className = "example";
    const title = document.createElement("strong"); title.textContent = `Example ${index + 1}`;
    const pre = document.createElement("pre"); pre.textContent = `Input\n${JSON.stringify(example.input, null, 2)}\n\nExpected\n${JSON.stringify(example.output, null, 2)}`;
    card.append(title, pre); els.examples.append(card);
  });
}

async function submitSolution() {
  if (!state.selected || !els.code.value.trim()) return;
  els.submitCode.disabled = true; els.judgeResult.hidden = true; els.judgeState.textContent = "Queued for judging…";
  try {
    const submission = await api(`/leetcode/problems/${encodeURIComponent(state.selected.id)}/submit`, {
      method: "POST", headers: { "Idempotency-Key": crypto.randomUUID() },
      body: JSON.stringify({ language: els.language.value, code: els.code.value })
    });
    for (let attempt = 0; attempt < 30; attempt += 1) {
      const result = await api(`/leetcode/submissions/${submission.id}`);
      els.judgeState.textContent = result.status === "QUEUED" ? "Waiting for a judge worker…" : result.status === "RUNNING" ? "Running hidden tests…" : "Judge finished.";
      if (!["QUEUED", "RUNNING"].includes(result.status)) { renderJudgeResult(result); return; }
      await sleep(750);
    }
    throw new Error("The judge is taking longer than expected. You can submit again shortly.");
  } catch (error) {
    els.judgeState.textContent = "Submission failed."; toast(error.message, true);
  } finally { els.submitCode.disabled = false; }
}

function renderJudgeResult(result) {
  els.judgeResult.hidden = false; els.judgeResult.className = `result ${result.status === "ACCEPTED" ? "accepted" : "rejected"}`;
  els.judgeResult.replaceChildren();
  const title = document.createElement("strong"); title.textContent = result.status.replaceAll("_", " ");
  const detail = document.createElement("span"); detail.textContent = `${result.passedCount ?? 0}/${result.totalCount ?? 0} tests · ${result.executionTimeMs ?? 0} ms${result.errorMessage ? ` · ${result.errorMessage}` : ""}`;
  els.judgeResult.append(title, detail);
}

function addTestCase(value = { input: {}, output: null, hidden: false }) {
  const row = document.createElement("div"); row.className = "test-row";
  row.innerHTML = `<label>Input JSON<textarea class="test-input" rows="5"></textarea></label><label>Expected output JSON<textarea class="test-output" rows="5"></textarea></label><label class="hidden-toggle"><input class="test-hidden" type="checkbox"> Hidden from solvers</label><button type="button" class="remove-test quiet">Remove</button>`;
  row.querySelector(".test-input").value = JSON.stringify(value.input, null, 2);
  row.querySelector(".test-output").value = JSON.stringify(value.output, null, 2);
  row.querySelector(".test-hidden").checked = Boolean(value.hidden);
  row.querySelector(".remove-test").addEventListener("click", () => { if (els.testCases.children.length > 1) row.remove(); else toast("A problem needs at least one test case.", true); });
  els.testCases.append(row);
}

function resetAdminForm() {
  state.editing = false; els.adminForm.reset(); els.adminId.disabled = false; els.adminFormTitle.textContent = "New problem";
  els.testCases.replaceChildren(); addTestCase({ input: { value: 1 }, output: 1, hidden: false });
  addTestCase({ input: { value: 2 }, output: 2, hidden: true }); els.adminMessage.textContent = "";
}

async function editSelectedProblem() {
  if (!isAdmin() || !state.selected) return;
  const problem = await api(`/leetcode/admin/problems/${encodeURIComponent(state.selected.id)}`);
  state.editing = true; switchView("admin"); els.adminFormTitle.textContent = `Edit ${problem.title}`;
  els.adminId.value = problem.id; els.adminId.disabled = true; els.adminTitle.value = problem.title;
  els.adminDifficulty.value = problem.difficulty; els.adminTags.value = (asObject(problem.tags, []) || []).join(", ");
  els.adminDescription.value = problem.description; const stubs = asObject(problem.codeStubs, {});
  els.adminJs.value = stubs.javascript || ""; els.adminPython.value = stubs.python || "";
  els.testCases.replaceChildren(); (asObject(problem.testCases, []) || []).forEach(addTestCase);
  if (!els.testCases.children.length) addTestCase();
}

function collectProblem() {
  const testCases = [...els.testCases.querySelectorAll(".test-row")].map((row, index) => {
    try {
      return { input: JSON.parse(row.querySelector(".test-input").value), output: JSON.parse(row.querySelector(".test-output").value), hidden: row.querySelector(".test-hidden").checked };
    } catch { throw new Error(`Test case ${index + 1} contains invalid JSON.`); }
  });
  const codeStubs = { javascript: els.adminJs.value.trim() };
  if (els.adminPython.value.trim()) codeStubs.python = els.adminPython.value.trim();
  return {
    id: els.adminId.value.trim(), title: els.adminTitle.value.trim(), difficulty: els.adminDifficulty.value,
    tags: els.adminTags.value.split(",").map((tag) => tag.trim()).filter(Boolean),
    description: els.adminDescription.value.trim(), codeStubs, testCases
  };
}

async function saveProblem(event) {
  event.preventDefault();
  try {
    const problem = collectProblem(); els.adminMessage.textContent = "Saving and validating test cases…";
    const path = state.editing ? `/leetcode/admin/problems/${encodeURIComponent(problem.id)}` : "/leetcode/admin/problems";
    await api(path, { method: state.editing ? "PUT" : "POST", body: JSON.stringify(problem) });
    els.adminMessage.textContent = "Problem saved."; toast("Problem and test cases saved.");
    await loadProblems(problem.id); await selectProblem(problem.id); switchView("solve");
  } catch (error) { els.adminMessage.textContent = error.message; toast(error.message, true); }
}

els.authForm.addEventListener("submit", async (event) => {
  event.preventDefault(); els.authMessage.textContent = "Signing in…";
  try {
    const username = els.username.value.trim(), password = els.password.value;
    if (state.authMode === "register") await api("/auth/register", { method: "POST", body: JSON.stringify({ username, password }) });
    await login(username, password); els.password.value = ""; els.authMessage.textContent = ""; await establishSession();
  } catch (error) { els.authMessage.textContent = error.message; }
});
document.querySelectorAll("[data-auth]").forEach((button) => button.addEventListener("click", () => {
  state.authMode = button.dataset.auth; document.querySelectorAll("[data-auth]").forEach((item) => item.classList.toggle("active", item === button));
  els.authSubmit.textContent = state.authMode === "login" ? "Enter StackRank" : "Create account";
}));
document.querySelectorAll("[data-view]").forEach((button) => button.addEventListener("click", () => switchView(button.dataset.view)));
els.logout.addEventListener("click", () => { localStorage.removeItem("appbuilder.jwt"); showSignedOut(); });
els.language.addEventListener("change", () => { const stubs = asObject(state.selected?.codeStubs, {}); els.code.value = stubs[els.language.value] || ""; });
els.submitCode.addEventListener("click", submitSolution); els.editProblem.addEventListener("click", editSelectedProblem);
els.addTest.addEventListener("click", () => addTestCase()); els.newProblem.addEventListener("click", resetAdminForm);
els.adminForm.addEventListener("submit", saveProblem);

resetAdminForm(); establishSession();
