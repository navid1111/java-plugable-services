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
  let slug = null, es = null;

  function add(type, text) {
    const el = document.createElement('div'); el.className = 'ev ' + type;
    el.innerHTML = '<span class="k">' + type.replace('_',' ') + '</span>' + (text || '');
    log.appendChild(el); log.scrollTop = log.scrollHeight;
  }
  function esc(s){ return (s||'').replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c])); }

  function connect() {
    es = new EventSource('/api/apps/' + slug + '/events');
    const on = (t, fn) => es.addEventListener(t, e => fn(JSON.parse(e.data)));
    on('user', d => add('user', esc(d.text)));
    on('thinking', d => { if ((d.text||'').trim()) add('thinking', esc(d.text)); });
    on('assistant_text', d => add('assistant_text', esc(d.text)));
    on('tool_use', d => add('tool_use', esc(d.tool) + ' — ' + esc(d.input)));
    on('tool_result', d => add('tool_result', (d.ok ? 'ok' : 'error') + ' — ' + esc(d.summary)));
    on('preview', d => { frame.src = d.url + '?t=' + Date.now(); openLink.href = d.url; openLink.style.display = 'inline'; statusEl.textContent = 'Live preview'; });
    on('error', d => add('error', esc(d.message)));
    on('done', d => { add('done', 'turn complete' + (d.is_error ? ' (with errors)' : '')); btn.disabled = false; });
  }

  async function send(text) {
    btn.disabled = true;
    if (!slug) {
      const r = await fetch('/api/apps', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({prompt: text}) });
      const d = await r.json(); slug = d.slug; statusEl.textContent = 'Building ' + slug + '…'; connect();
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
</script>
</body></html>
"""
