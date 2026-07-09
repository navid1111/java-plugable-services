"""The app-builder chat UI (single self-contained page)."""

INDEX_HTML = """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>App Builder — Agent</title>
<style>
  :root { color-scheme: dark; font-family: Inter, ui-sans-serif, system-ui, sans-serif; }
  * { box-sizing: border-box; }
  body { margin: 0; height: 100vh; display: grid; grid-template-columns: 420px 1fr;
         background: radial-gradient(circle at top left, #1e2748, #05060a 55%); color: #f8fafc; }
  .side { display: flex; flex-direction: column; border-right: 1px solid rgba(148,163,184,.18); min-height: 0; }
  header { padding: 18px 20px; border-bottom: 1px solid rgba(148,163,184,.14); }
  header h1 { margin: 0; font-size: 18px; letter-spacing: -.02em; }
  header p { margin: 6px 0 0; color: #94a3b8; font-size: 12px; }
  #log { flex: 1; overflow-y: auto; padding: 14px 16px; display: flex; flex-direction: column; gap: 8px; min-height: 0; }
  .ev { font-size: 12.5px; line-height: 1.5; padding: 8px 11px; border-radius: 10px; border: 1px solid rgba(148,163,184,.14); background: rgba(2,6,23,.5); }
  .ev.user { background: rgba(103,232,249,.1); border-color: rgba(103,232,249,.3); }
  .ev.tool_use { color: #a5b4fc; } .ev.tool_result { color: #86efac; } .ev.thinking { color: #94a3b8; font-style: italic; }
  .ev.error { color: #fca5a5; border-color: rgba(248,113,113,.4); } .ev.done { color: #67e8f9; }
  .ev .k { font-weight: 800; text-transform: uppercase; font-size: 10px; letter-spacing: .08em; opacity: .7; margin-right: 6px; }
  form { display: flex; gap: 8px; padding: 14px 16px; border-top: 1px solid rgba(148,163,184,.14); }
  textarea { flex: 1; resize: none; height: 60px; border-radius: 12px; border: 1px solid rgba(148,163,184,.24);
             background: rgba(2,6,23,.64); color: #f8fafc; padding: 10px 12px; font: inherit; outline: none; }
  button { border: 0; border-radius: 12px; padding: 0 16px; font-weight: 800; color: #041018; cursor: pointer;
           background: linear-gradient(135deg,#67e8f9,#a78bfa); }
  button:disabled { opacity: .5; cursor: wait; }
  .preview { display: flex; flex-direction: column; min-height: 0; }
  .bar { padding: 10px 16px; border-bottom: 1px solid rgba(148,163,184,.14); font-size: 12px; color: #94a3b8; display: flex; gap: 12px; align-items: center; }
  .bar a { color: #67e8f9; }
  iframe { flex: 1; width: 100%; border: 0; background: #fff; }
</style></head><body>
<div class="side">
  <header><h1>App Builder</h1><p>Describe an app. The agent scaffolds it from your Java plug backends and wires them through Kong.</p></header>
  <div id="log"></div>
  <form id="f"><textarea id="p" placeholder="make me a youtube-style app with video uploads and comments"></textarea><button id="b">Build</button></form>
</div>
<div class="preview">
  <div class="bar"><span id="status">No app yet.</span><a id="open" href="#" target="_blank" style="display:none">open ↗</a></div>
  <iframe id="frame" src="about:blank"></iframe>
</div>
<script>
  const log = document.getElementById('log'), form = document.getElementById('f'),
        promptEl = document.getElementById('p'), btn = document.getElementById('b'),
        statusEl = document.getElementById('status'), frame = document.getElementById('frame'),
        openLink = document.getElementById('open');
  let slug = new URLSearchParams(location.search).get('app'), es = null;

  function add(type, text) {
    const el = document.createElement('div'); el.className = 'ev ' + type;
    el.innerHTML = '<span class="k">' + type.replace('_',' ') + '</span>' + (text || '');
    log.appendChild(el); log.scrollTop = log.scrollHeight;
  }
  function esc(s){ return (s||'').replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c])); }

  function renderEvent(type, data) {
    if (type === 'user') add('user', esc(data.text));
    else if (type === 'thinking') { if ((data.text||'').trim()) add('thinking', esc(data.text)); }
    else if (type === 'assistant_text' || type === 'assistant_delta') add('assistant_text', esc(data.text));
    else if (type === 'tool_use') add('tool_use', esc(data.tool) + ' — ' + esc(data.input));
    else if (type === 'tool_result') add('tool_result', (data.ok ? 'ok' : 'error') + ' — ' + esc(data.summary));
    else if (type === 'preview') { frame.src = data.url + '?t=' + Date.now(); openLink.href = data.url; openLink.style.display = 'inline'; statusEl.textContent = 'Live preview'; }
    else if (type === 'error') add('error', esc(data.message));
    else if (type === 'done') { add('done', 'turn complete' + (data.is_error ? ' (with errors)' : '')); btn.disabled = false; }
  }

  async function loadHistory() {
    if (!slug) return;
    const r = await fetch('/api/apps/' + slug + '/history');
    if (!r.ok) return;
    const d = await r.json();
    log.innerHTML = '';
    (d.events || []).forEach(ev => renderEvent(ev.type, ev.data || {}));
  }

  function connect() {
    if (es) es.close();
    es = new EventSource('/api/apps/' + slug + '/events');
    const on = (t, fn) => es.addEventListener(t, e => fn(JSON.parse(e.data)));
    ['user','thinking','assistant_text','assistant_delta','tool_use','tool_result','preview','error','done']
      .forEach(t => on(t, d => renderEvent(t, d)));
  }

  async function send(text) {
    btn.disabled = true;
    if (!slug) {
      const r = await fetch('/api/apps', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({prompt: text}) });
      const d = await r.json(); slug = d.slug; window.history.replaceState(null, '', '?app=' + encodeURIComponent(slug)); statusEl.textContent = 'Building ' + slug + '…'; connect();
      await new Promise(res => setTimeout(res, 150)); // let SSE attach before first turn
    }
    await fetch('/api/apps/' + slug + '/message', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({text}) });
  }

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const text = promptEl.value.trim(); if (!text) return;
    promptEl.value = '';
    try { await send(text); } catch (err) { add('error', esc(String(err))); btn.disabled = false; }
  });

  if (slug) {
    statusEl.textContent = 'Loaded ' + slug;
    openLink.href = '/apps/' + slug + '/'; openLink.style.display = 'inline'; frame.src = '/apps/' + slug + '/';
    loadHistory().finally(connect);
  }
</script>
</body></html>
"""
