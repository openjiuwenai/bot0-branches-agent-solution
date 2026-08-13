const state = { config: null, runId: null, timer: null, wireAfter: 0, wire: [] };
const $ = (id) => document.getElementById(id);

async function request(url, options) {
  const response = await fetch(url, options);
  const text = await response.text();
  let body;
  try { body = JSON.parse(text); } catch { body = { error: text }; }
  if (!response.ok) throw new Error(body.error || `HTTP ${response.status}`);
  return body;
}

async function boot() {
  state.config = await request('/api/config');
  $('runtimeUrl').value = state.config.runtimeUrl;
  $('scenario').innerHTML = state.config.scenarios.map(s => `<option value="${s.id}">${s.title}</option>`).join('');
  updateScenario();
  await health();
  setInterval(health, 4000);
}

function updateScenario() {
  const selected = state.config.scenarios.find(s => s.id === $('scenario').value);
  $('scenarioCopy').textContent = selected?.description || '';
  $('continueWrap').hidden = $('scenario').value !== 'input-linear';
}

async function health() {
  try {
    const value = await request(`/api/runtime/health?url=${encodeURIComponent($('runtimeUrl').value)}`);
    $('healthDot').className = 'ok';
    $('healthText').textContent = value.status;
    $('connectionText').textContent = `${value.service} · ${$('runtimeUrl').value}`;
  } catch (error) {
    $('healthDot').className = 'bad';
    $('healthText').textContent = 'OFFLINE';
    $('connectionText').textContent = error.message;
  }
}

async function run(event) {
  event.preventDefault();
  resetView();
  $('runButton').disabled = true;
  $('runStatus').textContent = 'STARTING';
  try {
    const result = await request('/api/run', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ runtimeUrl: $('runtimeUrl').value, scenario: $('scenario').value,
        mode: $('mode').value, input: $('input').value, continueInput: $('continueInput').value })
    });
    state.runId = result.runId;
    state.timer = setInterval(pollRun, 350);
    await pollRun();
  } catch (error) {
    $('runStatus').textContent = 'FAILED';
    renderDiagnostics([{ at: new Date().toISOString(), message: error.message }]);
    $('runButton').disabled = false;
  }
}

async function pollRun() {
  if (!state.runId) return;
  try {
    const value = await request(`/api/runs/${encodeURIComponent(state.runId)}`);
    renderRun(value);
    if (!['QUEUED', 'RUNNING'].includes(value.status)) {
      clearInterval(state.timer); state.timer = null; $('runButton').disabled = false;
      await refreshWire();
    }
  } catch (error) {
    clearInterval(state.timer); $('runButton').disabled = false;
    renderDiagnostics([{ at: new Date().toISOString(), message: error.message }]);
  }
}

function renderRun(run) {
  $('runStatus').textContent = run.status;
  $('runStatus').style.color = run.status === 'COMPLETED' ? '#147d64' : run.status === 'FAILED' ? '#b23a3a' : '#9b6512';
  $('invocationRef').textContent = run.invocationRef || '-';
  $('toolExecutions').textContent = run.toolExecutions ?? 0;
  renderEvents(run.events || []);
  renderDiagnostics(run.diagnostics || []);
  renderHistory(run.treeHistory || []);
  renderTree(run.tree);
  $('snapshotJson').textContent = run.snapshot ? pretty(run.snapshot) : (run.error || 'Waiting for completion.');
}

function renderEvents(events) {
  $('eventCount').textContent = events.length;
  if (!events.length) { $('eventList').className = 'timeline empty-state'; $('eventList').textContent = 'No SDK events yet.'; return; }
  $('eventList').className = 'timeline';
  $('eventList').innerHTML = events.map(event => `<article class="event"><time>${clock(event.at)}</time>` +
    `<span class="event-type">${escapeHtml(event.type)}</span><pre>${escapeHtml(pretty(event.value))}</pre></article>`).join('');
}

function renderDiagnostics(items) {
  if (!items.length) { $('diagnostics').className = 'diagnostics empty-state'; $('diagnostics').textContent = 'Lifecycle and recovery diagnostics appear here.'; return; }
  $('diagnostics').className = 'diagnostics';
  $('diagnostics').innerHTML = items.slice().reverse().map(item => `<div class="diag"><time>${clock(item.at)}</time>${escapeHtml(item.message)}</div>`).join('');
}

function renderHistory(items) {
  $('historyCount').textContent = items.length;
  if (!items.length) { $('treeHistory').className = 'revision-list empty-state'; $('treeHistory').textContent = 'No revisions.'; return; }
  $('treeHistory').className = 'revision-list';
  $('treeHistory').innerHTML = items.slice().reverse().map(item => `<div class="revision"><strong>r${item.revision}</strong>` +
    `<span>${escapeHtml(item.completeness)} · ${escapeHtml(item.speakingPhase)}</span></div>`).join('');
}

function renderTree(tree) {
  if (!tree?.root) {
    $('treeCanvas').className = 'tree-canvas empty-state'; $('treeCanvas').textContent = 'No call tree snapshot available.';
    ['completeness','speakingPhase','currentSpeaker'].forEach(id => $(id).textContent = '-'); $('treeRevision').textContent = 'rev -'; return;
  }
  $('treeCanvas').className = 'tree-canvas';
  $('treeCanvas').innerHTML = nodeHtml(tree.root, tree.currentSpeaker, true);
  $('completeness').textContent = tree.completeness;
  $('speakingPhase').textContent = tree.speakingPhase;
  $('currentSpeaker').textContent = tree.currentSpeaker ? `${tree.currentSpeaker.agentId || '?'} / ${tree.currentSpeaker.taskId}` : '-';
  $('treeRevision').textContent = `rev ${tree.revision}`;
}

function nodeHtml(node, speaker, root) {
  const current = speaker && speaker.taskId === node.key.taskId && speaker.agentId === node.key.agentId;
  const artifacts = (node.artifacts || []).map(a => `<div class="artifact"><strong>${escapeHtml(a.artifactId)} · ${a.complete ? 'complete' : 'open'}</strong>` +
    `<pre>${escapeHtml(a.parts.map(part => part.text ?? pretty(part.data)).join('\n'))}</pre></div>`).join('');
  const children = (node.children || []).map(child => nodeHtml(child, speaker, false)).join('');
  return `<div class="tree-node ${root ? 'tree-root' : ''}"><div class="node-head ${current ? 'current' : ''}">` +
    `<span class="node-id">${escapeHtml(node.key.taskId)}</span><span class="node-state">${escapeHtml(node.state || 'unknown')}</span>` +
    `<span class="node-agent">${escapeHtml(node.key.agentId || 'root agent unknown')}</span>` +
    `${node.delegationIntent ? `<span class="node-intent">${escapeHtml(node.delegationIntent)}</span>` : ''}</div>${artifacts}${children}</div>`;
}

async function refreshWire() {
  try {
    const items = await request(`/api/runtime/requests?url=${encodeURIComponent($('runtimeUrl').value)}&after=0`);
    state.wire = items; $('wireCount').textContent = items.length;
    if (!items.length) { $('wireList').className = 'wire-list empty-state'; $('wireList').textContent = 'No A2A requests yet.'; return; }
    $('wireList').className = 'wire-list';
    $('wireList').innerHTML = items.slice().reverse().map(item => `<article class="wire-item"><div class="wire-head">` +
      `<strong>#${item.sequence} ${escapeHtml(item.method)}</strong><span>${clock(item.receivedAt)} · cursor ${escapeHtml(item.lastEventId || '-')}</span></div>` +
      `<pre>${escapeHtml(pretty(item.body))}</pre></article>`).join('');
  } catch (error) {
    $('wireList').className = 'wire-list empty-state'; $('wireList').textContent = error.message;
  }
}

function resetView() {
  state.runId = null;
  if (state.timer) clearInterval(state.timer);
  $('eventList').className = 'timeline empty-state'; $('eventList').textContent = 'Waiting for SDK events.';
  $('treeCanvas').className = 'tree-canvas empty-state'; $('treeCanvas').textContent = 'Waiting for call tree snapshots.';
  $('snapshotJson').textContent = 'Waiting for completion.';
  renderDiagnostics([]); renderHistory([]); $('eventCount').textContent = '0';
}

function activateTab(button) {
  document.querySelectorAll('.tab').forEach(tab => tab.classList.toggle('active', tab === button));
  document.querySelectorAll('.view').forEach(view => view.classList.toggle('active', view.id === `view-${button.dataset.view}`));
}
function pretty(value) { return JSON.stringify(value, null, 2); }
function clock(value) { return value ? new Date(value).toLocaleTimeString('zh-CN', { hour12: false }) : '-'; }
function escapeHtml(value) { return String(value ?? '').replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c])); }

$('runForm').addEventListener('submit', run);
$('scenario').addEventListener('change', updateScenario);
$('refreshWire').addEventListener('click', refreshWire);
document.querySelectorAll('.tab').forEach(button => button.addEventListener('click', () => activateTab(button)));
boot().catch(error => { $('connectionText').textContent = error.message; });
