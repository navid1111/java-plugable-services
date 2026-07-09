package com.example.appbuilder.ui;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UiController {

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String index() {
        return """
                <!doctype html>
                <html lang=\"en\">
                <head>
                    <meta charset=\"utf-8\">
                    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">
                    <title>Hermes App Builder</title>
                    <style>
                        :root { color-scheme: dark; font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, \"Segoe UI\", sans-serif; }
                        * { box-sizing: border-box; }
                        body { margin: 0; min-height: 100vh; background: radial-gradient(circle at top left, #293056, #080b14 42%, #05060a); color: #f8fafc; }
                        button, textarea { font: inherit; }
                        .shell { width: min(1180px, calc(100% - 32px)); margin: 0 auto; padding: 44px 0; }
                        .hero { display: grid; grid-template-columns: 1.1fr .9fr; gap: 24px; align-items: stretch; }
                        .panel { border: 1px solid rgba(148, 163, 184, .18); background: rgba(15, 23, 42, .72); border-radius: 28px; box-shadow: 0 24px 80px rgba(0, 0, 0, .35); backdrop-filter: blur(18px); }
                        .intro { padding: 34px; }
                        .eyebrow { color: #67e8f9; font-size: 13px; font-weight: 800; letter-spacing: .14em; text-transform: uppercase; }
                        h1 { font-size: clamp(38px, 7vw, 78px); line-height: .92; margin: 18px 0; letter-spacing: -.07em; }
                        p { color: #cbd5e1; line-height: 1.7; }
                        .composer { padding: 20px; display: flex; flex-direction: column; gap: 14px; }
                        textarea { width: 100%; min-height: 172px; resize: vertical; border: 1px solid rgba(148, 163, 184, .24); border-radius: 20px; background: rgba(2, 6, 23, .64); color: #f8fafc; padding: 18px; outline: none; }
                        textarea:focus { border-color: #67e8f9; box-shadow: 0 0 0 4px rgba(103, 232, 249, .12); }
                        button { border: 0; border-radius: 18px; padding: 15px 18px; color: #031019; background: linear-gradient(135deg, #67e8f9, #a78bfa); cursor: pointer; font-weight: 900; }
                        button:disabled { opacity: .55; cursor: wait; }
                        .grid { display: grid; grid-template-columns: 360px 1fr; gap: 24px; margin-top: 24px; }
                        .section { padding: 22px; }
                        h2 { margin: 0 0 14px; font-size: 18px; letter-spacing: -.02em; }
                        .plug, .card { border: 1px solid rgba(148, 163, 184, .16); border-radius: 18px; padding: 14px; margin-top: 10px; background: rgba(2, 6, 23, .4); }
                        .plug strong, .card strong { display: block; }
                        .meta { color: #94a3b8; font-size: 12px; margin-top: 6px; }
                        .badge { display: inline-flex; align-items: center; gap: 6px; margin-top: 10px; padding: 5px 9px; border-radius: 999px; font-size: 11px; font-weight: 900; letter-spacing: .05em; }
                        .ok { background: rgba(16, 185, 129, .16); color: #86efac; }
                        .dev { background: rgba(251, 191, 36, .16); color: #fde68a; }
                        .preview { min-height: 430px; background: linear-gradient(180deg, rgba(30, 41, 59, .8), rgba(2, 6, 23, .65)); }
                        .mock-window { border: 1px solid rgba(148, 163, 184, .18); border-radius: 22px; overflow: hidden; background: #f8fafc; color: #0f172a; }
                        .mock-bar { height: 42px; background: #e2e8f0; display: flex; gap: 7px; align-items: center; padding-left: 14px; }
                        .dot { width: 11px; height: 11px; border-radius: 99px; background: #94a3b8; }
                        .mock-body { padding: 22px; display: grid; gap: 12px; }
                        .mock-card { border: 1px solid #e2e8f0; border-radius: 16px; padding: 16px; background: white; }
                        .empty { color: #94a3b8; border: 1px dashed rgba(148, 163, 184, .35); border-radius: 18px; padding: 18px; }
                        @media (max-width: 900px) { .hero, .grid { grid-template-columns: 1fr; } }
                    </style>
                </head>
                <body>
                <main id=\"app-builder-ui\" class=\"shell\">
                    <section class=\"hero\">
                        <div class=\"panel intro\">
                            <div class=\"eyebrow\">Hermes Gateway · Java Plug Backends</div>
                            <h1>Build apps from your service plugs.</h1>
                            <p>Describe the app. The UI can be generated like Lovable/v0, but backend promises are grounded in the Java plug kits discovered in this repo.</p>
                        </div>
                        <form class=\"panel composer\" id=\"prompt-form\">
                            <textarea id=\"prompt\" placeholder=\"Example: make me a login app with media uploads and payments\">make me a login app with media uploads and payments</textarea>
                            <button id=\"generate\" type=\"submit\">Generate App</button>
                            <p id=\"status\" class=\"meta\">Loads /api/plugs and assesses prompts with /api/assess.</p>
                        </form>
                    </section>
                    <section class=\"grid\">
                        <aside class=\"panel section\">
                            <h2>Backend plug catalog</h2>
                            <div id=\"plug-list\" class=\"empty\">Loading /api/plugs…</div>
                        </aside>
                        <section class=\"panel section preview\">
                            <h2>Generated app preview</h2>
                            <div class=\"mock-window\">
                                <div class=\"mock-bar\"><span class=\"dot\"></span><span class=\"dot\"></span><span class=\"dot\"></span></div>
                                <div id=\"preview\" class=\"mock-body\">
                                    <div class=\"mock-card\"><strong>Prompt-driven UI preview</strong><p>Submit a prompt to see available backend-backed sections and developing placeholders.</p></div>
                                </div>
                            </div>
                        </section>
                    </section>
                </main>
                <script>
                    const plugList = document.querySelector('#plug-list');
                    const preview = document.querySelector('#preview');
                    const statusEl = document.querySelector('#status');
                    const form = document.querySelector('#prompt-form');
                    const promptEl = document.querySelector('#prompt');
                    const button = document.querySelector('#generate');

                    function badge(status) { return `<span class=\"badge ${status === 'AVAILABLE' ? 'ok' : 'dev'}\">${status}</span>`; }
                    function renderPlugs(plugs) {
                        if (!plugs.length) { plugList.className = 'empty'; plugList.textContent = 'No */plug service kits found.'; return; }
                        plugList.className = '';
                        plugList.innerHTML = plugs.map(plug => `<div class=\"plug\"><strong>${plug.displayName}</strong><div class=\"meta\">${plug.id} · ${plug.gatewayPaths.join(', ') || 'no gateway path yet'}</div>${badge(plug.status)}</div>`).join('');
                    }
                    function renderAssessment(data) {
                        const available = data.availableServiceIds.map(id => `<div class=\"mock-card\"><strong>${id}</strong><p>Available Java plug. The agent can wire this service through Kong.</p></div>`).join('');
                        const missing = data.developingCapabilities.map(name => `<div class=\"mock-card\"><strong>${name}</strong><p>No backend yet — the agent will mark this as being developed, not faked.</p></div>`).join('');
                        preview.innerHTML = available + missing || '<div class=\"mock-card\"><strong>No backend matches yet</strong><p>Try mentioning login, media, posts, comments, search, leetcode, or booking.</p></div>';
                    }
                    async function loadPlugs() {
                        const res = await fetch('/api/plugs');
                        renderPlugs(await res.json());
                    }
                    form.addEventListener('submit', async (event) => {
                        event.preventDefault();
                        button.disabled = true;
                        statusEl.textContent = 'Assessing prompt against the Java plug catalog…';
                        try {
                            const res = await fetch('/api/assess', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ prompt: promptEl.value }) });
                            renderAssessment(await res.json());
                            statusEl.innerHTML = 'Catalog grounded. App generation runs in the agent backend — see agent-backend/README.';
                        } catch (error) {
                            statusEl.textContent = 'Failed to assess prompt: ' + error;
                        } finally {
                            button.disabled = false;
                        }
                    });
                    loadPlugs().catch(error => { plugList.textContent = 'Failed to load plugs: ' + error; });
                </script>
                </body>
                </html>
                """;
    }
}
