"""The App Builder UI: friendly build progress with optional technical details."""

INDEX_HTML = """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>App Builder</title>
<style>
  :root {
    color-scheme: dark;
    font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    --bg: #080b12; --panel: #101522; --panel-2: #151c2c; --line: rgba(148,163,184,.16);
    --text: #f8fafc; --muted: #9aa7ba; --brand: #69e2ff; --violet: #a78bfa;
    --good: #4ade80; --bad: #fb7185;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; height: 100vh; display: grid; grid-template-columns: minmax(360px, 440px) 1fr;
    background: radial-gradient(circle at 8% 0%, #1b2852 0, var(--bg) 42%); color: var(--text);
  }
  .side { display: flex; flex-direction: column; min-height: 0; border-right: 1px solid var(--line); background: rgba(8,11,18,.76); backdrop-filter: blur(18px); }
  header { padding: 20px 20px 16px; border-bottom: 1px solid var(--line); }
  .eyebrow { color: var(--brand); font-size: 11px; font-weight: 800; letter-spacing: .12em; text-transform: uppercase; }
  header h1 { margin: 5px 0 0; font-size: 21px; letter-spacing: -.035em; }
  header p { margin: 7px 0 0; color: var(--muted); font-size: 13px; line-height: 1.5; }

  .scroll { flex: 1; min-height: 0; overflow-y: auto; padding: 16px; }
  .build-card { padding: 15px; border: 1px solid rgba(105,226,255,.22); border-radius: 16px; background: linear-gradient(145deg, rgba(105,226,255,.08), rgba(167,139,250,.07)); box-shadow: 0 18px 50px rgba(0,0,0,.2); }
  .build-card[hidden] { display: none; }
  .build-card.success { border-color: rgba(74,222,128,.34); }
  .build-card.failed { border-color: rgba(251,113,133,.38); }
  .build-head { display: flex; gap: 11px; align-items: flex-start; }
  .state-icon { width: 32px; height: 32px; flex: 0 0 auto; display: grid; place-items: center; border-radius: 50%; background: rgba(105,226,255,.13); color: var(--brand); font-weight: 900; }
  .building .state-icon::after { content: ""; width: 13px; height: 13px; border: 2px solid rgba(105,226,255,.3); border-top-color: var(--brand); border-radius: 50%; animation: spin .9s linear infinite; }
  .success .state-icon { background: rgba(74,222,128,.14); color: var(--good); }
  .failed .state-icon { background: rgba(251,113,133,.14); color: var(--bad); }
  @keyframes spin { to { transform: rotate(360deg); } }
  #buildTitle { font-size: 14px; font-weight: 800; }
  #buildMessage { margin-top: 3px; color: var(--muted); font-size: 12.5px; line-height: 1.45; }
  .progress-row { display: flex; justify-content: space-between; gap: 10px; margin-top: 14px; color: var(--muted); font-size: 11px; }
  .track { height: 7px; margin-top: 7px; overflow: hidden; border-radius: 99px; background: rgba(148,163,184,.14); }
  #progressBar { height: 100%; width: 0; border-radius: inherit; background: linear-gradient(90deg,var(--brand),var(--violet)); transition: width .7s ease; }
  .failed #progressBar { background: var(--bad); }
  .steps { display: grid; grid-template-columns: repeat(4, 1fr); gap: 5px; margin-top: 13px; }
  .step { color: #657187; font-size: 10px; text-align: center; }
  .step::before { content: ""; display: block; width: 7px; height: 7px; margin: 0 auto 5px; border-radius: 50%; background: #384257; }
  .step.active { color: #d7e1ef; }
  .step.active::before { background: var(--brand); box-shadow: 0 0 0 4px rgba(105,226,255,.1); }
  .step.complete { color: #8fa0b6; }
  .step.complete::before { background: var(--good); }

  #messages { display: flex; flex-direction: column; gap: 9px; margin-top: 14px; }
  .message { max-width: 92%; padding: 10px 12px; border-radius: 13px; font-size: 13px; line-height: 1.5; white-space: pre-wrap; }
  .message.welcome, .message.assistant { align-self: flex-start; background: var(--panel-2); border: 1px solid var(--line); color: #dbe5f2; }
  .message.user { align-self: flex-end; background: linear-gradient(135deg, rgba(105,226,255,.18), rgba(167,139,250,.18)); border: 1px solid rgba(105,226,255,.25); }
  .message.error { align-self: flex-start; color: #ffd8df; background: rgba(251,113,133,.1); border: 1px solid rgba(251,113,133,.28); }

  details { margin-top: 14px; border-top: 1px solid var(--line); padding-top: 12px; }
  summary { cursor: pointer; color: #7f8ca1; font-size: 11px; user-select: none; }
  #log { display: flex; flex-direction: column; gap: 5px; max-height: 230px; overflow-y: auto; margin-top: 9px; }
  .ev { padding: 7px 9px; border-radius: 8px; background: rgba(2,6,23,.55); color: #8996aa; font: 10.5px/1.4 ui-monospace, SFMono-Regular, Menlo, monospace; overflow-wrap: anywhere; }
  .ev.error { color: #fda4af; }
  .ev .k { color: #b6c2d4; font-weight: 800; margin-right: 7px; text-transform: uppercase; }

  form { display: grid; grid-template-columns: 1fr auto; gap: 9px; padding: 14px 16px 16px; border-top: 1px solid var(--line); background: rgba(8,11,18,.9); }
  textarea { resize: none; height: 68px; border-radius: 13px; border: 1px solid rgba(148,163,184,.24); background: rgba(2,6,23,.7); color: var(--text); padding: 11px 12px; font: inherit; outline: none; }
  textarea:focus { border-color: rgba(105,226,255,.55); box-shadow: 0 0 0 3px rgba(105,226,255,.07); }
  button { border: 0; border-radius: 13px; min-width: 88px; padding: 0 16px; font-weight: 850; color: #071018; cursor: pointer; background: linear-gradient(135deg,var(--brand),var(--violet)); }
  button:disabled { opacity: .52; cursor: wait; }

  .preview { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
  .bar { min-height: 48px; padding: 10px 16px; border-bottom: 1px solid var(--line); color: var(--muted); display: flex; justify-content: space-between; gap: 12px; align-items: center; font-size: 12px; }
  .bar-right { display: flex; gap: 12px; align-items: center; }
  .bar a { color: var(--brand); text-decoration: none; font-weight: 700; }
  .view-tab { min-width: auto; padding: 7px 10px; border: 1px solid transparent; border-radius: 9px; color: var(--muted); background: transparent; font-size: 11px; }
  .view-tab.active { color: var(--text); border-color: var(--line); background: var(--panel-2); }
  .view-tab:disabled { cursor: not-allowed; }
  [hidden] { display: none !important; }
  iframe { flex: 1; width: 100%; border: 0; background: #fff; }
  .architecture { flex: 1; min-height: 0; overflow: auto; padding: 22px; background: radial-gradient(circle at 50% 0%, rgba(105,226,255,.08), transparent 45%); }
  .architecture-inner { width: min(1240px, 100%); margin: 0 auto; }
  .architecture-heading { display: flex; justify-content: space-between; gap: 20px; align-items: flex-start; }
  .architecture h2 { margin: 0; font-size: 20px; }
  .architecture p { margin: 6px 0 0; color: var(--muted); font-size: 12.5px; line-height: 1.5; }
  .origin-badge { flex: 0 0 auto; padding: 5px 8px; border: 1px solid var(--line); border-radius: 99px; color: var(--brand); font-size: 10px; text-transform: uppercase; letter-spacing: .08em; }
  .architecture-workbench { display: grid; grid-template-columns: 220px minmax(540px, 1fr); gap: 14px; margin-top: 17px; min-height: 520px; }
  .service-palette { padding: 14px; overflow: auto; border: 1px solid var(--line); border-radius: 15px; background: rgba(8,11,18,.72); }
  .service-palette h3 { margin: 0; font-size: 12px; }
  .service-palette p { margin: 5px 0 12px; color: var(--muted); font-size: 10.5px; line-height: 1.45; }
  .service-list { display: grid; gap: 7px; }
  .service-chip { min-width: 0; width: 100%; padding: 9px 10px; display: grid; gap: 3px; text-align: left; color: #dbe5f2; border: 1px solid var(--line); border-radius: 10px; background: var(--panel-2); }
  .service-chip small { color: var(--muted); font-size: 9px; font-weight: 500; }
  .service-chip.connected { color: var(--good); border-color: rgba(74,222,128,.28); }
  .diagram-surface { position: relative; min-height: 520px; overflow: auto; border: 1px solid rgba(105,226,255,.2); border-radius: 15px; color: #0f172a; background-color: #f8fafc; background-image: radial-gradient(#cbd5e1 1px, transparent 1px); background-size: 20px 20px; }
  #architectureEdges { position: absolute; inset: 0; pointer-events: none; overflow: visible; }
  #architectureNodes { position: absolute; inset: 0; }
  .architecture-node { position: absolute; width: 160px; min-height: 68px; padding: 11px 12px; display: grid; align-content: center; gap: 3px; border: 2px solid #cbd5e1; border-radius: 12px; background: white; box-shadow: 0 9px 24px rgba(15,23,42,.12); cursor: grab; user-select: none; touch-action: none; }
  .architecture-node:active { cursor: grabbing; }
  .architecture-node.gateway { color: white; border-color: #0891b2; background: linear-gradient(135deg,#0e7490,#4338ca); }
  .architecture-node.app { border-color: #8b5cf6; }
  .architecture-node.service.connected { border-color: #22c55e; box-shadow: 0 9px 24px rgba(34,197,94,.18); }
  .architecture-node.service.disconnected { border-style: dashed; border-color: #94a3b8; opacity: .7; }
  .architecture-node.actor { width: 120px; border-radius: 999px; text-align: center; }
  .node-kind { color: #64748b; font-size: 8px; font-weight: 850; letter-spacing: .12em; text-transform: uppercase; }
  .gateway .node-kind { color: #bae6fd; }
  .node-label { color: inherit; font-size: 12px; font-weight: 850; }
  .node-path { color: #64748b; font: 9px ui-monospace, monospace; }
  .node-remove { position: absolute; top: 4px; right: 5px; min-width: 21px; width: 21px; height: 21px; padding: 0; display: grid; place-items: center; border-radius: 50%; color: #64748b; background: #eef2f7; }
  .canvas-hint { position: absolute; left: 14px; bottom: 12px; color: #64748b; font-size: 10px; pointer-events: none; }
  .source-label { display: block; margin-top: 18px; color: #dbe5f2; font-size: 12px; font-weight: 750; }
  .source-editor { margin-top: 14px; }
  #architectureSource { width: 100%; height: 190px; margin-top: 7px; resize: vertical; border-radius: 13px; font: 12px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace; }
  .architecture-actions { display: flex; justify-content: space-between; gap: 12px; align-items: center; margin-top: 10px; }
  #architectureStatus { color: var(--muted); font-size: 11px; }
  .architecture-buttons { display: flex; gap: 8px; }
  .secondary-button, .apply-button { min-width: auto; min-height: 36px; padding: 7px 12px; border-radius: 10px; font-size: 11px; }
  .secondary-button { color: var(--text); border: 1px solid var(--line); background: var(--panel-2); }

  @media (max-width: 850px) {
    body { height: auto; min-height: 100vh; grid-template-columns: 1fr; grid-template-rows: minmax(560px, auto) 70vh; }
    .side { border-right: 0; border-bottom: 1px solid var(--line); }
    .scroll { max-height: 480px; }
    .architecture-workbench { grid-template-columns: 1fr; }
    .service-list { grid-template-columns: repeat(2,minmax(0,1fr)); }
  }
</style></head><body>
<aside class="side">
  <header>
    <div class="eyebrow">AI workspace</div>
    <h1>App Builder</h1>
    <p>Describe what you want. We’ll build it, connect the available services, check it, and show the preview here.</p>
  </header>
  <div class="scroll">
    <section id="buildCard" class="build-card" hidden aria-live="polite">
      <div class="build-head">
        <div class="state-icon" id="stateIcon" aria-hidden="true"></div>
        <div><div id="buildTitle">Getting things ready</div><div id="buildMessage">Reading your request and checking available features.</div></div>
      </div>
      <div class="progress-row"><span>Estimated progress</span><span id="timeLabel">Usually 5–10 minutes with backend tests</span></div>
      <div class="track" role="progressbar" aria-label="Estimated build progress" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0"><div id="progressBar"></div></div>
      <div class="steps">
        <div class="step" data-step="0">Prepare</div><div class="step" data-step="1">Build</div>
        <div class="step" data-step="2">Connect</div><div class="step" data-step="3">Check</div>
      </div>
    </section>
    <div id="messages"><div class="message welcome">What would you like to build? You can describe the pages, style, and features in normal language.</div></div>
    <details id="technical"><summary>Technical details <span id="eventCount"></span></summary><div id="log"></div></details>
  </div>
  <form id="f">
    <textarea id="p" aria-label="Describe your app" placeholder="Example: Build a YouTube-style app with uploads, comments, search, and a dark theme"></textarea>
    <button id="b">Build app</button>
  </form>
</aside>
<main class="preview">
  <div class="bar"><span id="status">Your preview will appear here.</span><div class="bar-right">
    <button id="previewTab" class="view-tab active" type="button">Preview</button>
    <button id="architectureTab" class="view-tab" type="button">Architecture</button>
    <a id="open" href="#" target="_blank" rel="noopener" hidden>Open preview ↗</a>
  </div></div>
  <iframe id="frame" title="Generated app preview" src="about:blank"></iframe>
  <section id="architecturePane" class="architecture" hidden>
    <div class="architecture-inner">
      <div class="architecture-heading">
        <div><h2>Service architecture</h2><p id="servicesSummary">Connect the scaffolded service nodes to show what the app should use.</p></div>
        <span id="architectureOrigin" class="origin-badge">Generated</span>
      </div>
      <div class="architecture-workbench">
        <aside class="service-palette">
          <h3>Available service plugs</h3>
          <p>Every service is scaffolded on the canvas. Click one here to connect or disconnect it from Kong.</p>
          <div id="serviceList" class="service-list"></div>
        </aside>
        <div id="diagram" class="diagram-surface">
          <svg id="architectureEdges" aria-hidden="true"></svg>
          <div id="architectureNodes"></div>
          <div class="canvas-hint">Drag nodes to arrange the diagram · click a connected service in the palette to unplug it</div>
        </div>
      </div>
      <details class="source-editor">
        <summary>Advanced Mermaid context</summary>
        <label class="source-label" for="architectureSource">Mermaid source</label>
        <textarea id="architectureSource" spellcheck="false" aria-describedby="architectureHelp" placeholder="flowchart LR&#10;  App --> Gateway"></textarea>
        <p id="architectureHelp">You can still edit Mermaid directly. Direct edits become agent context while the visual layout remains available.</p>
      </details>
      <div class="architecture-actions">
        <span id="architectureStatus" role="status">Not saved yet</span>
        <div class="architecture-buttons">
          <button id="saveArchitecture" class="secondary-button" type="button">Save context</button>
          <button id="applyArchitecture" class="apply-button" type="button">Save &amp; update app</button>
        </div>
      </div>
    </div>
  </section>
</main>
<script>
  const log = document.getElementById('log'), messages = document.getElementById('messages'),
        form = document.getElementById('f'), promptEl = document.getElementById('p'),
        btn = document.getElementById('b'), statusEl = document.getElementById('status'),
        frame = document.getElementById('frame'), openLink = document.getElementById('open'),
        buildCard = document.getElementById('buildCard'), buildTitle = document.getElementById('buildTitle'),
        buildMessage = document.getElementById('buildMessage'), progressBar = document.getElementById('progressBar'),
        timeLabel = document.getElementById('timeLabel'), progressTrack = document.querySelector('.track'),
        eventCount = document.getElementById('eventCount'),
        previewTab = document.getElementById('previewTab'), architectureTab = document.getElementById('architectureTab'),
        architecturePane = document.getElementById('architecturePane'), diagram = document.getElementById('diagram'),
        architectureEdges = document.getElementById('architectureEdges'), architectureNodes = document.getElementById('architectureNodes'),
        serviceList = document.getElementById('serviceList'),
        architectureSource = document.getElementById('architectureSource'), servicesSummary = document.getElementById('servicesSummary'),
        architectureOrigin = document.getElementById('architectureOrigin'), architectureStatus = document.getElementById('architectureStatus'),
        saveArchitectureBtn = document.getElementById('saveArchitecture'), applyArchitectureBtn = document.getElementById('applyArchitecture');

  const phases = {
    prepare: { progress: 10, step: 0, title: 'Getting things ready', message: 'Reading your request and checking available features.' },
    plan: { progress: 24, step: 0, title: 'Planning your app', message: 'Working out the screens, layout, and best way to build it.' },
    build: { progress: 48, step: 1, title: 'Creating the interface', message: 'Building the pages and interactions you asked for.' },
    connect: { progress: 68, step: 2, title: 'Connecting your features', message: 'Wiring the interface to the available backend services.' },
    check: { progress: 86, step: 3, title: 'Running final checks', message: 'Testing the important flows and fixing connection problems.' }
  };

  let slug = new URLSearchParams(location.search).get('app'), es = null;
  let building = false, startedAt = 0, progress = 0, progressTimer = null;
  let technicalEvents = 0, agentReportedError = false, lastPhase = '', architectureDirty = false;
  let architectureSourceEdited = false, architectureGraph = {nodes: [], edges: []}, serviceCatalog = [];

  function addMessage(kind, text) {
    if (!text) return;
    const el = document.createElement('div');
    el.className = 'message ' + kind; el.textContent = text;
    messages.appendChild(el); el.scrollIntoView({block: 'nearest'});
  }

  function addTechnical(type, text) {
    if (!text) return;
    const el = document.createElement('div'); el.className = 'ev ' + type;
    const key = document.createElement('span'); key.className = 'k'; key.textContent = type.replaceAll('_', ' ');
    el.append(key, document.createTextNode(String(text).slice(0, 1200)));
    log.appendChild(el); log.scrollTop = log.scrollHeight;
    while (log.children.length > 200) log.firstElementChild.remove();
    technicalEvents += 1; eventCount.textContent = '(' + technicalEvents + ')';
  }

  function showView(name) {
    const architectureVisible = name === 'architecture';
    frame.hidden = architectureVisible; architecturePane.hidden = !architectureVisible;
    previewTab.classList.toggle('active', !architectureVisible);
    architectureTab.classList.toggle('active', architectureVisible);
    statusEl.textContent = architectureVisible ? 'Architecture and service plugs' : (slug ? 'Live preview' : 'Your preview will appear here.');
    openLink.hidden = architectureVisible || !slug;
    if (architectureVisible) renderArchitecture();
  }

  function markArchitectureDirty(message) {
    architectureDirty = true;
    architectureOrigin.textContent = 'Unsaved';
    architectureStatus.textContent = message || 'Unsaved visual changes';
  }

  function renderArchitecture() {
    architectureNodes.replaceChildren();
    const catalogById = Object.fromEntries(serviceCatalog.map(item => [item.id, item]));
    const nodeById = Object.fromEntries((architectureGraph.nodes || []).map(node => [node.id, node]));
    const connectedNodeIds = new Set((architectureGraph.edges || []).filter(edge => edge.source === 'gateway').map(edge => edge.target));

    (architectureGraph.nodes || []).forEach(node => {
      const element = document.createElement('div');
      const isConnected = node.type !== 'service' || connectedNodeIds.has(node.id);
      element.className = 'architecture-node ' + node.type + (node.type === 'service' ? (isConnected ? ' connected' : ' disconnected') : '');
      element.dataset.nodeId = node.id;
      element.style.left = (node.x || 0) + 'px'; element.style.top = (node.y || 0) + 'px';
      const kind = document.createElement('span'); kind.className = 'node-kind';
      kind.textContent = node.type === 'service' ? (isConnected ? 'service · connected' : 'service · available') : node.type;
      const label = document.createElement('span'); label.className = 'node-label'; label.textContent = node.label || node.id;
      element.append(kind, label);
      if (node.type === 'service') {
        const detail = catalogById[node.serviceId] || {};
        const path = document.createElement('span'); path.className = 'node-path'; path.textContent = (detail.gatewayPaths || []).join(', ');
        const remove = document.createElement('button'); remove.type = 'button'; remove.className = 'node-remove';
        remove.title = isConnected ? 'Disconnect from Kong' : 'Connect to Kong'; remove.textContent = isConnected ? '−' : '+';
        remove.addEventListener('click', event => { event.stopPropagation(); toggleService(node.serviceId); });
        element.append(path, remove);
      }
      element.addEventListener('pointerdown', event => beginNodeDrag(event, node.id));
      architectureNodes.appendChild(element);
    });

    const width = Math.max(diagram.clientWidth, 900, ...(architectureGraph.nodes || []).map(node => (node.x || 0) + 200));
    const height = Math.max(diagram.clientHeight, 520, ...(architectureGraph.nodes || []).map(node => (node.y || 0) + 105));
    architectureEdges.style.width = width + 'px'; architectureEdges.style.height = height + 'px';
    architectureNodes.style.width = width + 'px'; architectureNodes.style.height = height + 'px';
    architectureEdges.setAttribute('viewBox', `0 0 ${width} ${height}`);
    architectureEdges.innerHTML = '<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="#64748b"></path></marker></defs>';
    (architectureGraph.edges || []).forEach(edge => {
      const source = nodeById[edge.source], target = nodeById[edge.target];
      if (!source || !target) return;
      const sourceWidth = source.type === 'actor' ? 120 : 160;
      const targetWidth = target.type === 'actor' ? 120 : 160;
      const x1 = (source.x || 0) + sourceWidth, y1 = (source.y || 0) + 34;
      const x2 = (target.x || 0), y2 = (target.y || 0) + 34;
      const bend = Math.max(28, Math.abs(x2 - x1) * .45);
      const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      path.setAttribute('d', `M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}`);
      path.setAttribute('fill', 'none'); path.setAttribute('stroke', '#64748b'); path.setAttribute('stroke-width', '2');
      path.setAttribute('marker-end', 'url(#arrow)'); architectureEdges.appendChild(path);
    });

    const connected = new Set((architectureGraph.nodes || []).filter(node => connectedNodeIds.has(node.id)).map(node => node.serviceId));
    serviceList.replaceChildren();
    serviceCatalog.forEach(service => {
      const chip = document.createElement('button'); chip.type = 'button';
      chip.className = 'service-chip' + (connected.has(service.id) ? ' connected' : '');
      const label = document.createElement('span'); label.textContent = (connected.has(service.id) ? '✓ ' : '+ ') + service.displayName;
      const paths = document.createElement('small'); paths.textContent = (service.gatewayPaths || []).join(', ');
      chip.append(label, paths); chip.addEventListener('click', () => toggleService(service.id));
      serviceList.appendChild(chip);
    });
  }

  function beginNodeDrag(event, nodeId) {
    if (event.button !== 0 || event.target.closest('.node-remove')) return;
    const node = (architectureGraph.nodes || []).find(item => item.id === nodeId); if (!node) return;
    event.preventDefault();
    const startX = event.clientX, startY = event.clientY, originalX = node.x || 0, originalY = node.y || 0;
    function move(moveEvent) {
      const canvasWidth = Math.max(diagram.clientWidth, 900, ...architectureGraph.nodes.map(item => (item.x || 0) + 200));
      const canvasHeight = Math.max(diagram.clientHeight, 520, ...architectureGraph.nodes.map(item => (item.y || 0) + 105));
      node.x = Math.max(0, Math.min(canvasWidth - 120, originalX + moveEvent.clientX - startX));
      node.y = Math.max(0, Math.min(canvasHeight - 68, originalY + moveEvent.clientY - startY));
      renderArchitecture();
    }
    function end() { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', end); markArchitectureDirty('Node layout changed'); }
    window.addEventListener('pointermove', move); window.addEventListener('pointerup', end, {once: true});
  }

  function toggleService(serviceId) {
    const node = (architectureGraph.nodes || []).find(item => item.serviceId === serviceId); if (!node) return;
    const edgeId = 'gateway-' + serviceId;
    const connected = architectureGraph.edges.some(edge => edge.source === 'gateway' && edge.target === node.id);
    if (connected) architectureGraph.edges = architectureGraph.edges.filter(edge => !(edge.source === 'gateway' && edge.target === node.id));
    else architectureGraph.edges.push({id: edgeId, source: 'gateway', target: node.id});
    architectureSourceEdited = false; markArchitectureDirty(connected ? 'Service disconnected from Kong' : 'Service connected to Kong'); renderArchitecture();
  }

  function setArchitecture(data, force) {
    data = data || {};
    if (force || !architectureDirty) {
      architectureSource.value = data.source || '';
      architectureDirty = false; architectureSourceEdited = false;
    }
    architectureGraph = data.graph || {nodes: [], edges: []};
    serviceCatalog = data.catalog || [];
    const services = data.connectedServices || data.services || [];
    servicesSummary.textContent = services.length
      ? 'Connected through Kong: ' + services.join(', ')
      : 'No services are connected yet. Use the palette or node buttons to define the app.';
    architectureOrigin.textContent = data.origin === 'user' ? 'User context' : 'Generated';
    architectureStatus.textContent = data.origin === 'user'
      ? 'Saved as agent context'
      : 'Generated from the current app';
    renderArchitecture();
  }

  async function loadArchitecture() {
    if (!slug) return;
    const response = await fetch('/api/apps/' + encodeURIComponent(slug) + '/architecture');
    if (!response.ok) return;
    setArchitecture(await response.json(), true);
  }

  async function saveArchitecture(applyToApp) {
    if (!slug) {
      architectureStatus.textContent = 'Build an app before saving architecture context.';
      return;
    }
    architectureStatus.textContent = 'Saving…';
    const response = await fetch('/api/apps/' + encodeURIComponent(slug) + '/architecture', {
      method: 'PUT', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(architectureSourceEdited
        ? {source: architectureSource.value}
        : {source: architectureSource.value, graph: architectureGraph})
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      architectureStatus.textContent = data.detail || 'Could not save this Mermaid source.';
      return;
    }
    setArchitecture(data, true);
    architectureStatus.textContent = applyToApp ? 'Saved. Updating the app…' : 'Saved. The agent will use it on the next update.';
    if (applyToApp) {
      showView('preview');
      await send('Update the app to follow the user-edited architecture diagram. Keep all service calls within the available plug contracts.');
    }
  }

  function setProgress(value) {
    progress = Math.max(progress, Math.min(100, value));
    progressBar.style.width = progress + '%';
    progressTrack.setAttribute('aria-valuenow', String(Math.round(progress)));
  }

  function updateSteps(activeStep, finished) {
    document.querySelectorAll('.step').forEach((el, index) => {
      el.classList.toggle('complete', finished || index < activeStep);
      el.classList.toggle('active', !finished && index === activeStep);
    });
  }

  function setPhase(name) {
    if (!building || !phases[name]) return;
    const phase = phases[name];
    if (lastPhase && phases[lastPhase] && phase.progress < phases[lastPhase].progress) return;
    lastPhase = name;
    buildTitle.textContent = phase.title; buildMessage.textContent = phase.message;
    setProgress(phase.progress); updateSteps(phase.step, false);
  }

  function elapsedText(seconds) {
    if (seconds < 60) return seconds + 's elapsed';
    const minutes = Math.floor(seconds / 60), rest = seconds % 60;
    return minutes + 'm ' + String(rest).padStart(2, '0') + 's elapsed';
  }

  function beginBuild() {
    building = true; agentReportedError = false; lastPhase = '';
    startedAt = Date.now(); progress = 0; buildCard.hidden = false;
    buildCard.className = 'build-card building'; btn.disabled = true; btn.textContent = 'Building…';
    applyArchitectureBtn.disabled = true;
    document.getElementById('stateIcon').textContent = '';
    timeLabel.textContent = 'Usually 5–10 minutes with backend tests'; statusEl.textContent = 'Building your app…';
    setPhase('prepare');
    clearInterval(progressTimer);
    progressTimer = setInterval(() => {
      const seconds = Math.max(0, Math.floor((Date.now() - startedAt) / 1000));
      timeLabel.textContent = elapsedText(seconds) + ' · usually 5–10 min';
      const estimate = Math.min(89, 12 + 78 * (1 - Math.exp(-seconds / 240)));
      setProgress(estimate);
      if (seconds > 150 && lastPhase !== 'check') {
        buildMessage.textContent = 'Still working—larger apps can take a little longer. Your progress is safe.';
      }
    }, 1000);
  }

  function finishBuild(failed) {
    building = false; clearInterval(progressTimer); progressTimer = null;
    btn.disabled = false; btn.textContent = slug ? 'Update app' : 'Build app';
    applyArchitectureBtn.disabled = false;
    buildCard.hidden = false;
    if (failed) {
      buildCard.className = 'build-card failed';
      document.getElementById('stateIcon').textContent = '!';
      buildTitle.textContent = 'This build needs attention';
      buildMessage.textContent = 'The app was not marked ready because a final check failed. See the message below and try again.';
      timeLabel.textContent = 'Stopped after final checks'; statusEl.textContent = 'Build needs a fix';
      updateSteps(3, false);
    } else {
      buildCard.className = 'build-card success';
      document.getElementById('stateIcon').textContent = '✓';
      setProgress(100); updateSteps(4, true);
      buildTitle.textContent = 'Your app is ready';
      buildMessage.textContent = 'The build and backend contract checks passed. Try it in the preview.';
      timeLabel.textContent = elapsedText(Math.max(0, Math.floor((Date.now() - startedAt) / 1000)));
      statusEl.textContent = 'Live preview ready';
    }
  }

  function toolPhase(data) {
    const text = ((data.tool || '') + ' ' + (data.input || '')).toLowerCase();
    if (/verify|test|curl|check|lint/.test(text)) return 'check';
    if (/fetch|api|backend|gateway|auth|upload|service/.test(text)) return 'connect';
    if (/write|edit|patch|apply|css|html|javascript|jsx|tsx|react|app\\.js|index\\.html/.test(text)) return 'build';
    return 'plan';
  }

  function technicalText(type, data) {
    if (type === 'user' || type === 'assistant_text' || type === 'assistant_delta' || type === 'thinking') return data.text || '';
    if (type === 'tool_use') return (data.tool || 'tool') + (data.input ? ' — ' + data.input : '');
    if (type === 'tool_result') return (data.ok === false ? 'failed — ' : 'ok — ') + (data.summary || 'completed');
    if (type === 'error') return data.message || data.userMessage || 'Unknown error';
    if (type === 'preview') return (data.stage === 'draft' ? 'Draft checkpoint: ' : 'Verified preview: ') + (data.url || '');
    if (type === 'architecture') return 'Architecture updated: ' + ((data.services || []).join(', ') || 'no backend services');
    if (type === 'verification') return data.userMessage || data.report || 'Testing live backend endpoints';
    if (type === 'done' || type === 'build_complete') return data.is_error ? 'completed with errors' : 'completed';
    return JSON.stringify(data || {});
  }

  function renderEvent(type, data, options) {
    options = options || {};
    if (type !== 'assistant_delta') addTechnical(type, technicalText(type, data));
    if (type === 'user') {
      agentReportedError = false;
      addMessage('user', data.text || '');
      if (!options.replay && !building) beginBuild();
    } else if (type === 'thinking') {
      if (building && data.category === 'connection_retry') {
        buildMessage.textContent = data.userMessage || 'Connection interrupted briefly—continuing automatically.';
      } else if (building && /test|verify|check|lint/i.test(data.text || '')) setPhase('check');
      else if (building) setPhase('plan');
    } else if (type === 'assistant_text') {
      addMessage('assistant', data.text || '');
    } else if (type === 'tool_use') {
      setPhase(toolPhase(data));
    } else if (type === 'tool_result' && data.ok === false) {
      if (building) {
        setPhase('check');
        buildMessage.textContent = 'A check found something to fix. The builder is working on it.';
      }
    } else if (type === 'verification') {
      if (building) {
        setPhase('check'); setProgress(data.status === 'passed' ? 96 : 90);
        buildMessage.textContent = data.userMessage || 'Testing the real backend endpoints used by this app.';
      }
    } else if (type === 'architecture') {
      if (!options.replay) setArchitecture(data, false);
    } else if (type === 'preview') {
      frame.src = data.url + '?t=' + Date.now(); openLink.href = data.url; openLink.hidden = false;
      if (data.stage === 'draft' && building) {
        setPhase('build'); setProgress(Math.max(progress, 52)); statusEl.textContent = 'Live React draft · still building';
        buildMessage.textContent = 'A usable checkpoint is ready. You can preview it while the builder continues.';
      } else if (building) { setPhase('check'); setProgress(94); }
    } else if (type === 'error') {
      addMessage('error', data.userMessage || 'Something went wrong while building. Please try again.');
      agentReportedError = true;
    } else if (type === 'done') {
      agentReportedError = agentReportedError || Boolean(data.is_error);
      if (building) setPhase('check');
    } else if (type === 'build_complete') {
      if (options.replay && !building) { startedAt = Date.now(); progress = 0; }
      finishBuild(Boolean(data.is_error) || agentReportedError);
    }
  }

  async function loadHistory() {
    if (!slug) return;
    const response = await fetch('/api/apps/' + encodeURIComponent(slug) + '/history');
    if (!response.ok) return;
    const data = await response.json(), events = data.events || [];
    messages.innerHTML = ''; log.innerHTML = ''; technicalEvents = 0; eventCount.textContent = '';
    events.forEach(event => renderEvent(event.type, event.data || {}, {replay: true}));
    let lastUser = -1, lastComplete = -1, lastLegacyTerminal = -1;
    events.forEach((event, index) => {
      if (event.type === 'user') lastUser = index;
      if (event.type === 'build_complete') lastComplete = index;
      if (event.type === 'done' || event.type === 'preview') lastLegacyTerminal = index;
    });
    if (lastComplete < 0 && lastLegacyTerminal > lastUser) {
      startedAt = Date.now(); progress = 0; finishBuild(agentReportedError);
    } else if (lastUser > lastComplete) {
      beginBuild(); setPhase('plan');
      buildMessage.textContent = 'Reconnected. The builder is still working on your app.';
    }
  }

  function connect() {
    if (es) es.close();
    es = new EventSource('/api/apps/' + encodeURIComponent(slug) + '/events');
    ['user','thinking','assistant_text','assistant_delta','tool_use','tool_result','verification','architecture','preview','error','done','build_complete']
      .forEach(type => es.addEventListener(type, event => renderEvent(type, JSON.parse(event.data))));
    es.onerror = () => {
      if (building) buildMessage.textContent = 'Reconnecting to the builder… your work is still safe.';
    };
  }

  async function send(text) {
    beginBuild();
    if (!slug) {
      const response = await fetch('/api/apps', {
        method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({prompt: text})
      });
      if (!response.ok) throw new Error('Could not create an app workspace.');
      const data = await response.json(); slug = data.slug;
      window.history.replaceState(null, '', '?app=' + encodeURIComponent(slug)); connect();
      await loadArchitecture();
      await new Promise(resolve => setTimeout(resolve, 150));
    }
    const response = await fetch('/api/apps/' + encodeURIComponent(slug) + '/message', {
      method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({text})
    });
    if (!response.ok) throw new Error(response.status === 409 ? 'The builder is already working on this app.' : 'Could not start the build.');
  }

  form.addEventListener('submit', async event => {
    event.preventDefault();
    const text = promptEl.value.trim(); if (!text || building) return;
    promptEl.value = '';
    try { await send(text); }
    catch (error) {
      addTechnical('error', String(error));
      addMessage('error', String(error.message || error) + ' Please try again.');
      finishBuild(true);
    }
  });

  frame.addEventListener('load', () => {
    if (!building && slug && statusEl.textContent === 'Opening your app…') statusEl.textContent = 'Preview opened';
  });

  architectureSource.addEventListener('input', () => {
    architectureSourceEdited = true; markArchitectureDirty('Unsaved Mermaid changes');
  });
  previewTab.addEventListener('click', () => showView('preview'));
  architectureTab.addEventListener('click', () => showView('architecture'));
  saveArchitectureBtn.addEventListener('click', () => saveArchitecture(false));
  applyArchitectureBtn.addEventListener('click', async () => {
    try { await saveArchitecture(true); }
    catch (error) {
      architectureStatus.textContent = String(error.message || error);
      if (building) finishBuild(true);
    }
  });
  window.addEventListener('resize', () => { if (!architecturePane.hidden) renderArchitecture(); });

  if (slug) {
    statusEl.textContent = 'Opening your app…';
    openLink.href = '/apps/' + encodeURIComponent(slug) + '/'; openLink.hidden = false;
    frame.src = '/apps/' + encodeURIComponent(slug) + '/';
    Promise.all([loadHistory(), loadArchitecture()]).finally(connect);
  }
</script>
</body></html>
"""
