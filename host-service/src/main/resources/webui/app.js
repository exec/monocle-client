'use strict';
const $ = id => document.getElementById(id);
const number = value => Number(value || 0).toLocaleString();
const terminal = status => ['Complete', 'Failed', 'Cancelled'].includes(status);
const headings = {
  overview: ['THE BIG PICTURE', 'Operations overview', 'A live pulse on your workers and the work ahead.'],
  crews: ['TEAM OPERATIONS', 'Your crews', 'Separate teams, separate work. One shared control room.'],
  workers: ['THE ROSTER', 'Your workers', 'Who is here, where they are, and what is holding them up.'],
  jobs: ['THE WORK AHEAD', 'Active jobs', 'Host decisions are authoritative—even when workers are offline.'],
  stashes: ['RESOURCE INTELLIGENCE', 'Your stashes', 'Observed contents, not guaranteed stock. Unscanned is never empty.'],
  history: ['OFF THE WORKBENCH', 'Job history', 'Finished work stays here. Cleanup receipts are never silently erased.']
};
let token = '', demo = false, snapshot = null, page = 'overview', refreshedAt = 0, refreshing = false, polling;
let busy = false, packaged = null, pendingSubmission = null, inspecting = null;
let generation = 0;
const WAIT = 'return function(ctx)\n if ctx.state.waited then return bot.done() end\n ctx.state.waited=true\n return bot.wait(200)\nend';
const STASH_SCAN = 'return function(ctx)\n if ctx.state.started then return bot.done(ctx.result) end\n ctx.state.started=true\n return bot.stash_scan(ctx.args)\nend';
const stashDetails = new Map();

function el(tag, cls = '', text = '') {
  const node = document.createElement(tag); if (cls) node.className = cls; if (text !== '') node.textContent = text; return node;
}
function button(text, action, disabled = false, cls = '', key = '') {
  const node = el('button', cls, text); node.type = 'button'; node.disabled = disabled;
  if (key) node.dataset.key = key; node.addEventListener('click', action); return node;
}
function badge(text, tone = '') { return el('span', 'pill ' + tone, text); }
function tone(status) {
  return /healthy|running|building|complete|ready|authenticated/i.test(status) ? 'healthy'
    : /failed|stopped|offline|error/i.test(status) ? 'bad' : /wait|paused|inspection|restock|resupply|suspend/i.test(status) ? 'attention' : '';
}
function writable() { return !demo && !!token && !!snapshot && snapshot.status === 'Running' && Date.now() - refreshedAt < 5000 && !busy; }
function notice(message, success = false) { $('notice').textContent = message; $('notice').className = 'banner' + (success ? ' success' : ''); $('notice').hidden = !message; }
async function api(path, request) {
  const attempt = generation;
  let response, body;
  try {
  response = await fetch('/ui/api/' + path, {
    method: request ? 'POST' : 'GET', credentials: 'omit', mode: 'same-origin', cache: 'no-store', signal: AbortSignal.timeout(8000),
    headers: { Authorization: 'Bearer ' + token, ...(request ? { 'Content-Type': 'application/json' } : {}) },
    ...(request ? { body: JSON.stringify(request) } : {})
  });
  body = await response.json();
  } catch (error) {
    const failure = new Error('Could not confirm the host response: ' + error.message);
    failure.uncertain = true; throw failure;
  }
  if (!response.ok) {
    if (response.status === 403) { if (attempt === generation) token = ''; throw new Error('Access rejected. Reconnect with the host API token from this allowed origin.'); }
    throw new Error(body.error || 'Request rejected (HTTP ' + response.status + ')');
  }
  return body;
}
async function refresh() {
  if (refreshing || !token && !demo) return;
  if (demo) { refreshedAt = Date.now(); render(); return; }
  refreshing = true;
  const attempt = generation;
  try {
    const data = await api('status'); if (attempt !== generation) return;
    snapshot = data; refreshedAt = Date.now(); render(); refreshStashPanels();
    if (pendingSubmission && [...snapshot.tasks, ...snapshot.history].some(t => t.id === pendingSubmission.id)) {
      pendingSubmission = null; $('job-dialog').close(); notice('Job accepted by the host.', true);
    }
  } catch (error) {
    if (attempt !== generation) return;
    notice('Host snapshot is stale: ' + error.message + ' Controls are disabled until a fresh snapshot returns.');
    $('connection').textContent = token ? 'Connection lost' : 'Access rejected'; $('connection').className = 'pill bad';
    refreshedAt = 0; if (snapshot) render();
  } finally { refreshing = false; }
}
function startPolling() {
  clearTimeout(polling);
  polling = setTimeout(async () => { await refresh(); if (token || demo) startPolling(); }, document.hidden ? 5000 : 1000);
}
function unlock() {
  $('login').hidden = true; $('app').hidden = false; $('demo-banner').hidden = !demo;
  render(); startPolling();
}
function logout() {
  if (pendingSubmission && !confirm('A submission may have reached the host. Disconnect anyway? Inspect Jobs before submitting replacement work.')) return;
  token = ''; demo = false; snapshot = null; packaged = null; pendingSubmission = null; inspecting = null; refreshedAt = 0;
  generation++;
  clearTimeout(polling); document.querySelectorAll('dialog[open]').forEach(d => d.close());
  $('token').value = ''; $('app').hidden = true; $('login').hidden = false; $('login-error').textContent = ''; notice('');
}
$('login-form').addEventListener('submit', async event => {
  event.preventDefault(); demo = false; token = $('token').value.trim(); $('token').value = '';
  const attempt = ++generation;
  const submit = event.submitter; submit.disabled = true; $('login-error').textContent = '';
  try { const data = await api('status'); if (attempt !== generation) return; snapshot = data; refreshedAt = Date.now(); unlock(); }
  catch (error) { if (attempt === generation) { token = ''; $('login-error').textContent = error.message; } }
  finally { submit.disabled = false; }
});
$('logout').addEventListener('click', logout);
$('refresh').addEventListener('click', refresh);
document.addEventListener('visibilitychange', () => { if (!document.hidden && (token || demo)) { refresh(); startPolling(); } });
document.querySelectorAll('[data-page]').forEach(node => node.addEventListener('click', () => { page = node.dataset.page; render(); }));
document.querySelectorAll('.close-dialog').forEach(node => node.addEventListener('click', () => node.closest('dialog').close()));

function section(title, subtitle = '') {
  const header = el('div', 'section-heading'); header.append(el('h2', '', title), el('span', '', subtitle)); $('content').append(header);
}
function empty(title, detail) { const node = el('div', 'empty'); node.append(el('h3', '', title), el('p', '', detail)); return node; }
function workerState(worker) {
  const native = snapshot.highways[worker.crew]?.workers?.find(w => w.id === worker.id);
  const task = snapshot.tasks.find(t => Object.values(t.runs || {}).some(r => r.id === worker.current));
  const run = task?.runs?.[worker.id];
  return { native, task, run, status: !worker.connected ? 'Offline' : native?.phase || run?.status || (worker.reconciled ? 'Ready' : 'Reconciling'),
    detail: native?.status || run?.detail || (worker.current ? 'Execution ' + worker.current : 'Ready for assignment') };
}
function render() {
  if (!snapshot) return;
  for (const [crew, state] of Object.entries(snapshot.highways)) {
    if (!state.length) continue;
    sampleCurrentRate(crew, state.execution, state.progress, refreshedAt);
    const supplying = phase => /resuppl|returning from suppl/i.test(phase || '');
    rememberForecast(crew,'crew',state.roadPrediction,(state.workers || []).some(w=>supplying(w.phase)));
    for (const worker of state.workers || []) rememberForecast(crew,worker.id,worker.roadPrediction,supplying(worker.phase));
  }
  for (const crew of currentRates.keys()) if (!snapshot.highways[crew]?.length) currentRates.delete(crew);
  const [kicker, title, subtitle] = headings[page];
  $('breadcrumb').textContent = page[0].toUpperCase() + page.slice(1); $('page-kicker').textContent = kicker;
  $('page-title').textContent = title; $('page-subtitle').textContent = subtitle;
  document.querySelectorAll('[data-page]').forEach(node => { node.classList.toggle('active', node.dataset.page === page); node.setAttribute('aria-current', node.dataset.page === page ? 'page' : 'false'); });
  const connected = snapshot.workers.filter(w => w.connected).length;
  const alerts = snapshot.tasks.filter(t => /paused|inspection|suspend/i.test(t.status) || t.detail && /wait|deferred|inspect/i.test(t.detail)).length;
  $('crew-count').textContent = snapshot.crews.length; $('worker-count').textContent = connected; $('job-count').textContent = snapshot.tasks.length;
  $('metrics').replaceChildren();
  for (const [label, value, detail] of [['Connected workers', connected, 'of ' + snapshot.workers.length + ' reported workers'], ['Active crews', Object.values(snapshot.highways).filter(c => c.length).length, snapshot.crews.length + ' configured crews'], ['Live jobs', snapshot.tasks.length, snapshot.history.length + ' in separate history'], ['Needs attention', alerts, snapshot.status === 'Running' ? 'Coordinator online' : snapshot.status]]) {
    const metric = el('article', 'metric'); metric.append(el('span', '', label), el('strong', '', String(value)), el('small', '', detail)); $('metrics').append(metric);
  }
  const fresh = Date.now() - refreshedAt < 5000;
  $('connection').textContent = demo ? 'Demo mode' : !token ? 'Access rejected' : fresh ? snapshot.status === 'Running' ? '● Host connected' : snapshot.status : 'Stale snapshot';
  $('connection').className = 'pill ' + (demo ? '' : fresh && token ? tone(snapshot.status) : 'bad');
  $('updated').textContent = demo ? 'Sample data · no live commands' : refreshedAt ? 'Updated ' + new Date(refreshedAt).toLocaleTimeString() : 'Last snapshot unavailable';
  $('new-job').disabled = !writable();
  const focused = $('content').contains(document.activeElement) ? document.activeElement.dataset.key : null;
  $('content').replaceChildren();
  if (page === 'overview') {
    section('Host policies', 'Applied by this coordinator; workers cannot enable them');
    const policy=el('article','card');const label=el('label','worker-option');const toggle=document.createElement('input');toggle.type='checkbox';toggle.checked=!!snapshot.autoTpy;toggle.disabled=!writable();
    toggle.addEventListener('change',()=>control({op:'host-settings',autoTpy:toggle.checked}));
    label.append(toggle,el('span','','Auto TPY · accept an observed same-crew /tpa after 10 ticks'));policy.append(label,el('p','hint','Only exact online crew identities on the same server qualify. The policy is advertised by the host and cannot be enabled from a worker.'));$('content').append(policy);
  }
  if (page === 'overview' || page === 'crews') {
    section(page === 'crews' ? 'Configured crews' : 'Crew activity', 'Work is isolated by crew key');
    const cards = el('div', 'cards'); snapshot.crews.forEach(crew => cards.append(crewCard(crew))); $('content').append(cards);
    if (page === 'crews') $('content').append(button('Create crew', () => {
      const name=prompt('New crew name');if(name)control({op:'crew-create',id:crypto.randomUUID(),name});
    }, !writable()));
  }
  if (page === 'overview' || page === 'workers') { section('Worker roster', 'Fresh server observations, not guessed positions'); $('content').append(workerTable(snapshot.workers)); }
  if (page === 'jobs' || page === 'history') {
    const jobs = page === 'history' ? snapshot.history : snapshot.tasks;
    section(page === 'history' ? 'Finished jobs' : 'Job queue', jobs.length + ' records');
    jobs.forEach(task => $('content').append(jobCard(task)));
    if (!jobs.length) $('content').append(empty(page === 'history' ? 'A clean slate' : 'Nothing in the queue', page === 'history' ? 'Finished jobs appear here, not in your active work.' : 'Connect workers, then dispatch a workflow to get moving.'));
  }
  if (page === 'stashes') {
    section('Overall resources', 'Combined observations across all exported and host-defined stashes');
    $('content').append(overallResources(snapshot.stashes || []));
    section('Stashes', 'Read-only experimental discovery · quantities include shulker contents');
    for (const stash of snapshot.stashes || []) $('content').append(stashCard(stash));
    if (!(snapshot.stashes || []).length) $('content').append(empty('No stash observations yet', 'Select a cuboid with Stash Manager’s wooden pickaxe, or dispatch an Inspect stash job with its bounds.'));
  }
  if (page === 'overview') {
    const events = Object.entries(snapshot.highways).flatMap(([crew, h]) => (h.events || []).map(e => ({ ...e, crew }))).sort((a, b) => b.time - a.time).slice(0, 6);
    section('From the field', 'Latest crew events'); $('content').append(eventList(events));
    if (snapshot.lastConnectionError || snapshot.telemetryError) notice(snapshot.lastConnectionError || snapshot.telemetryError);
  }
  if (focused) $('content').querySelector('[data-key="' + CSS.escape(focused) + '"]')?.focus({ preventScroll: true });
  if ($('inspect-dialog').open) { updateChat();document.querySelectorAll('.managed-control').forEach(n=>{n.disabled=!writable();}); }
}
function progress(node, value, total) {
  const percent = Math.max(0, Math.min(100, 100 * (value || 0) / Math.max(1, total || 1)));
  const info = el('div', 'card-progress'); info.append(el('strong', '', percent.toFixed(1) + '%'), el('span', '', number(value) + ' of ' + number(total) + ' blocks mined'));
  const bar = el('progress'); bar.max = Math.max(1, total || 1); bar.value = Math.min(value || 0, total || 1); bar.setAttribute('aria-label', 'Verified highway progress'); node.append(info, bar);
}
function resourceLine(counts, crew = false) {
  if (!counts) return el('p', 'resources muted', 'Resources awaiting worker telemetry');
  const total = crew ? counts.total : counts.inventory; const chest = crew ? null : counts.enderChest;
  const values = ['Obsidian ' + number(total.obsidian), 'Pickaxes ' + number(total.pickaxes), 'Food ' + number(total.food)];
  if (chest) values.push('EChest ' + (counts.enderChestKnown ? [number(chest.obsidian) + ' obs', number(chest.pickaxes) + ' picks', number(chest.food) + ' food'].join(' / ') : 'not scanned'));
  else if (!counts.enderChestKnown) values.push('some EChests not scanned');
  return el('p', 'resources', values.join(' · '));
}
const currentRates = new Map();
function sampleCurrentRate(crew, execution, value, time) {
  let state = currentRates.get(crew);
  if (!state || state.execution !== execution || value < state.samples.at(-1)[1]) {
    state = { execution, samples: [], rate: null, forecasts: new Map() }; currentRates.set(crew, state);
  }
  if (state.samples.at(-1)?.[0] === time) return;
  state.samples.push([time, value]);
  while (state.samples.length > 2 && (state.samples[1][0] <= time - 5000 || state.samples.length > 32)) state.samples.shift();
  const [start, blocks] = state.samples[0];
  state.rate = time - start >= 1000 ? Math.max(0, (value - blocks) * 1000 / (time - start)) : null;
}
function rememberForecast(crew,key,prediction,supplying) {
  const forecasts=currentRates.get(crew)?.forecasts;if(!forecasts)return;
  if(prediction?.nextBlocks)forecasts.set(key,{prediction:{...prediction},held:false});
  else if(supplying&&forecasts.has(key))forecasts.get(key).held=true;
  else forecasts.delete(key);
}
function predictionLine(prediction, crew, worker = 'crew') {
  const cached=currentRates.get(crew)?.forecasts.get(worker);
  const held=!prediction?.nextBlocks&&cached?.held;
  if(held)prediction=cached.prediction;
  const rate=currentRates.get(crew)?.rate;
  const current=rate==null?'Learning current rate':rate.toFixed(1)+' blocks/sec current';
  const estimate=prediction?.nextBlocks?'estimated '+Number(prediction.blocksPerSecond).toFixed(1)+' blocks/sec over next '+forecastDuration(prediction.nextBlocks,Number(prediction.blocksPerSecond))+(held?' (held during resupply)':''):'visible-road estimate unavailable';
  const line=el('p','hint');
  line.append(speedSpan(rate,current),el('span','',' · '),speedSpan(prediction?.nextBlocks?Number(prediction.blocksPerSecond):null,estimate));
  line.title='Current speed measures verified crew progress over roughly five seconds since this panel connected. '+(prediction?.nextBlocks?'Forecast coverage: '+number(prediction.nextBlocks)+' road blocks (~'+number(Math.ceil(prediction.nextBlocks/16))+' visible chunks). Duration = loaded distance / estimated speed. ':'')+'Not a whole-job ETA or a promised speed change at its end. Netherrack has zero mining cost; supplies and unpredictable obstructions are excluded.';return line;
}
function forecastDuration(blocks, rate) {
  const seconds=blocks/rate;
  if(rate<=0||!Number.isFinite(seconds))return 'unknown duration';
  if(seconds<60)return '~'+Math.ceil(seconds)+' sec';
  if(seconds<3600)return '~'+Math.ceil(seconds/60)+' min';
  if(seconds<86400)return '~'+(seconds/3600).toFixed(1)+' hr';
  return '~'+(seconds/86400).toFixed(1)+' days';
}
function speedSpan(rate, text) {
  const band=rate==null?'unknown':rate<=1?'slow':rate<3?'steady':rate<5?'fast':rate<6?'rapid':'exceptional';
  const span=el('span','speed speed-'+band,text);
  span.title=rate==null?'Awaiting speed data':band[0].toUpperCase()+band.slice(1)+' · '+rate.toFixed(1)+' blocks/sec';
  return span;
}
function crewCard(crew) {
  const native = snapshot.highways[crew] || {}; const workers = snapshot.workers.filter(w => w.crew === crew && w.connected);
  const task = snapshot.tasks.find(t => t.crew === crew && t.nativeDefinition) || snapshot.tasks.find(t => t.crew === crew);
  const card = el('article', 'card'); const header = el('div', 'card-header');
  header.append(el('span', 'card-kicker', 'CREW / ' + crew.toUpperCase()), badge(native.phase || 'idle', tone(native.phase || 'idle'))); card.append(header);
  const title=el('h3');title.append(button(snapshot.crewLabels?.[crew] || crew,()=>inspect('crew',crew),false,'text-button'));
  card.append(title, el('p', 'muted', task?.name || 'Ready for the next job'));
  if (task?.workflowName && task.workflowName !== task.name) card.append(el('p','muted','Workflow: '+task.workflowName));
  if (native.length) progress(card, native.progress, native.length);
  else card.append(el('p', 'hint', workers.length ? 'Connected and awaiting work.' : 'No workers connected to this crew.'));
  card.append(resourceLine(native.resourceCounts, true));
  card.append(predictionLine(native.roadPrediction,crew));
  const bottom = el('div', 'card-bottom'); bottom.append(el('span', '', workers.length + ' worker' + (workers.length === 1 ? '' : 's') + (native.supplyOwner ? ' · detached supplies' : '')),
    button('Manage →', () => inspect('crew', crew), false, '', 'crew-' + crew)); card.append(bottom); return card;
}
function workerTable(workers) {
  if (!workers.length) return empty('Your workers will appear here', 'Enable worker connections and use this host’s crew key.');
  const panel = el('div', 'table-panel'); const table = el('table'); const head = el('thead'); const labels = el('tr');
  ['Worker', 'Crew', 'State', 'Resources', 'Position', 'Observation', ''].forEach(text => labels.append(el('th', '', text))); head.append(labels); table.append(head);
  const body = el('tbody');
  for (const worker of workers) {
    const state = workerState(worker); const row = el('tr'); const name = el('td');name.append(button(worker.name,()=>inspect('worker',worker.id),false,'text-button'), el('small', '', state.task?.name || state.detail));
    const position = state.native || worker; const coords = ['x', 'y', 'z'].every(k => Number.isFinite(position[k])) ? [position.x, position.y, position.z].map(Math.floor).join(', ') : '—';
    const status = el('td'); status.append(badge(state.status, tone(state.status))); const actions = el('td');
    actions.append(button('Manage', () => inspect('worker', worker.id), false, '', 'worker-' + worker.id));
    const resources=el('td');resources.append(resourceLine(state.native?.resourceCounts),predictionLine(state.native?.roadPrediction,worker.crew,worker.id));
    row.append(name, el('td', '', worker.crew), status, resources, el('td', '', coords), el('td', '', worker.positionFresh ? Math.round(worker.observationAgeMs || 0) + ' ms ago' : 'Stale'), actions); body.append(row);
  }
  table.append(body); panel.append(table); return panel;
}
function jobActions(task) {
  const actions = el('div', 'actions'); const dead = terminal(task.status);
  if (!dead) {
    const paused = !!task.paused || /paused|inspection|suspended/i.test(task.status);
    actions.append(button(paused ? 'Resume' : 'Pause', () => control({ op: paused ? 'resume' : 'pause', id: task.id }), !writable(), '', task.id + '-pause'));
    if(task.nativeDefinition)actions.append(button(task.publicJoin?'Close joining':'Open joining',()=>control({op:'public-join',id:task.id,enabled:!task.publicJoin}),!writable(),'','public-'+task.id));
    actions.append(button('Cancel job', () => control({ op: 'cancel', id: task.id }, 'Cancel “' + task.name + '”? Offline workers reconcile this decision when they reconnect. Outstanding resource recovery stays recorded.'), !writable(), 'danger', task.id + '-cancel'));
  } else actions.append(button('Delete history', () => control({ op: 'delete', id: task.id }, 'Delete this finished history record?'), !writable() || task.cleanupPending, 'danger', task.id + '-delete'));
  actions.append(button('Inspect', () => inspect('job', task.id), false, '', task.id + '-inspect')); return actions;
}
function jobCard(task) {
  const card = el('article', 'card job-card'); const header = el('div', 'card-header'); header.append(el('h3', '', task.name), badge(task.status, tone(task.status))); card.append(header);
  const meta = el('div', 'job-meta'); [task.crew, task.workflowName || 'Workflow', 'Priority ' + task.priority, Object.keys(task.runs || {}).length + ' workers',task.publicJoin?'Public joining':'Invite only'].forEach(t => meta.append(el('span', '', t))); card.append(meta);
  const total = task.nativeDefinition?.length; if (total) progress(card, task.highwayProgress || 0, total);
  card.append(el('p', 'job-detail', task.detail || 'Awaiting dispatch'), jobActions(task));
  if (task.cleanupPending) card.append(el('p', 'hint', 'Decision is final; delivery/recovery acknowledgements remain outstanding. History deletion is protected.'));
  return card;
}
function eventList(events) {
  if (!events.length) return empty('Quiet on the wire', 'Crew events will show up as work progresses.');
  const list = el('ul', 'events'); for (const event of events) { const row = el('li'); row.append(el('time', '', new Date(event.time).toLocaleTimeString()), el('span', '', (event.crew ? event.crew + ' · ' : '') + event.detail)); list.append(row); } return list;
}
async function control(request, confirmation) {
  if (!writable() || confirmation && !confirm(confirmation)) return;
  busy = true; render();
  try { await api('control', request); notice('Host accepted ' + request.op + '. Worker cleanup and delivery may continue independently.', true); }
  catch (error) { notice(error.message + ' Refresh status before retrying; the command may already have reached the host.'); }
  finally { busy = false; await refresh(); if ($('inspect-dialog').open) showInspection(); }
}
function inspect(kind, id) { inspecting = { kind, id }; showInspection(); if (!$('inspect-dialog').open) $('inspect-dialog').showModal(); }
function jsonDetails(title, value) { const node = el('details'); node.append(el('summary', '', title), el('pre', '', JSON.stringify(value, null, 2))); return node; }
function managedButton(text, action, cls='') { return button(text,action,!writable(),'managed-control '+cls); }
function assignFromManagement(crew, worker) {
  $('new-job').click();$('job-crew').value=crew;updateWorkers();
  if(worker)for(const input of $('job-workers').querySelectorAll('input'))input.checked=input.value===worker;
  updateScope();
}
function updateChat() {
  const feed=$('management-chat');if(!feed||!inspecting)return;
  const rows=(inspecting.kind==='worker'?(snapshot.chat||[]):(snapshot.crewChat||snapshot.chat||[])).filter(row=>inspecting.kind==='worker'?row.worker===inspecting.id:row.crew===inspecting.id);
  const fingerprint=JSON.stringify(rows);if(feed.dataset.snapshot===fingerprint)return;
  const atBottom=feed.scrollTop+feed.clientHeight>=feed.scrollHeight-20;feed.dataset.snapshot=fingerprint;feed.replaceChildren();
  for(const row of rows) {
    const line=el('div','chat-line '+row.direction), attribution=el('small','chat-recipient',row.name);
    attribution.title=row.direction==='sent'?'Sending worker':row.direction==='error'?'Affected worker':'Receiving workers';
    const message=el('span','chat-message');
    for(const part of row.parts||[{text:row.text}])message.append(el('span',chatColor(part.color),part.text));
    line.append(el('time','',new Date(row.at).toLocaleTimeString()),attribution,message);feed.append(line);
  }
  if(!rows.length)feed.append(el('p','hint','No chat captured yet. Chat starts when an updated worker connects; this feed is session-only.'));
  if(atBottom)feed.scrollTop=feed.scrollHeight;
}
const chatColors=new Map();
function chatColor(color) {
  if(!/^#[0-9a-f]{6}$/i.test(color||''))return '';
  color=color.toLowerCase();
  if(chatColors.has(color))return chatColors.get(color);
  if(chatColors.size>=4096)return '';
  const name='chat-rgb-'+color.slice(1);
  // Validated RGB only; CSSOM keeps the existing strict CSP without inline styles.
  document.styleSheets[0].insertRule('.chat-message .'+name+'{color:'+color+'}',document.styleSheets[0].cssRules.length);
  chatColors.set(color,name);return name;
}
function chatPanel(content, crew, worker) {
  const panel=el('section','management-section');panel.append(el('h3','','Game chat'));
  const feed=el('div','chat-feed');feed.id='management-chat';feed.setAttribute('role','log');feed.setAttribute('aria-label','Worker game chat');panel.append(feed);
  const form=el('form','chat-compose');const input=el('input');input.maxLength=256;input.required=true;input.placeholder=worker?'Message or /server command':'Send through every connected worker in this crew';input.setAttribute('aria-label','Chat message or server command');
  const send=el('button','managed-control primary','Send');send.type='submit';send.disabled=!writable();form.append(input,send);
  form.addEventListener('submit',async e=>{e.preventDefault();if(!writable()||!input.value.trim())return;
    if(!worker&&!confirm('Send this message/command from every connected worker in this crew?'))return;
    send.disabled=true;const text=input.value;try {const result=await api('control',{op:'chat',crew,...(worker?{worker}:{}),text,commandId:crypto.randomUUID()});
      input.value='';notice(result.status+' ('+result.delivered.length+' workers).',true);
    } catch(error) {notice(error.message+(error.uncertain?' Delivery is uncertain—inspect the chat feed before sending again.':''));}finally{await refresh();send.disabled=!writable();}
  });panel.append(form,el('p','hint','Slash commands go to the Minecraft server. No offline queue or automatic retry. Received chat is tagged by worker; “sent” confirms client submission, not server acceptance.'));
  content.append(panel);
}
function managementControls(content, crew, worker) {
  const panel=el('section','management-section');panel.append(el('h3','','Work & configuration'));
  panel.append(managedButton(worker?'Assign work to this worker':'Assign crew',()=>assignFromManagement(crew,worker)));
  const jobs=snapshot.tasks.filter(t=>t.crew===crew&&(!worker||t.runs?.[worker]));
  for(const task of jobs) {
    panel.append(jobCard(task));if(worker)panel.append(priorityControls(task,worker));
    const details=el('details');details.append(el('summary','','Live module configuration · '+task.name));
    const form=el('form');const textarea=el('textarea');textarea.rows=5;textarea.value='{"auto-eat":{"active":true,"settings":"{}"}}';textarea.setAttribute('aria-label','Module configuration JSON');
    const submit=el('button','managed-control','Apply configuration');submit.type='submit';submit.disabled=!writable();form.append(textarea,submit);
    form.addEventListener('submit',e=>{e.preventDefault();if(!writable())return;try {const modules=JSON.parse(textarea.value);control({op:'configure',id:task.id,...(worker?{worker}:{}),modules});}catch(error){notice(error.message);}});
    details.append(el('p','hint','JSON keyed by module ID: active boolean + settings SNBT string. Applies to '+(worker?'this worker':'all workers assigned to this job')+'. Job-owned executors cannot be changed here.'),form);
    for(const [id,run] of Object.entries(task.runs||{}))if(!worker||id===worker)details.append(el('p','hint',(snapshot.workers.find(w=>w.id===id)?.name||id)+': revision '+(run.configRevision||0)+(run.configError?' · '+run.configError:'')));
    panel.append(details);
  }
  if(!jobs.length)panel.append(el('p','hint','No active jobs. Assign a workflow to travel, drop items, change modules/profiles, wait, teleport, or build.'));
  content.append(panel);
}
function crewControls(content, crew, worker) {
  const panel=el('section','management-section');panel.append(el('h3','',worker?'Crew membership':'Crew management'));const actions=el('div','actions');
  if(worker) {
    const select=el('select');select.setAttribute('aria-label','Destination crew');for(const id of snapshot.crews){const option=el('option','',snapshot.crewLabels?.[id]||id);option.value=id;select.append(option);}select.value=crew;
    actions.append(select,managedButton('Move worker',()=>control({op:'crew-move',crew:select.value,worker},'Move this worker? Its jobs must be finished/cancelled and cleanup acknowledged first.')));
  } else {
    actions.append(managedButton('Rename crew',()=>{const name=prompt('Crew name',snapshot.crewLabels?.[crew]||crew);if(name)control({op:'crew-rename',crew,name});}),
      managedButton('Delete crew',()=>control({op:'crew-delete',crew},'Delete this crew? The host refuses if workers, jobs, or recovery would be orphaned.'),'danger'));
  }
  panel.append(actions);content.append(panel);
}
function priorityControls(task, workerId) {
  const row = el('div', 'priority-row'); const input = el('input'); input.type = 'number'; input.min = -1000; input.max = 1000; input.step = 1;
  input.value = workerId ? task.overrides?.[workerId] ?? task.priority : task.priority; input.setAttribute('aria-label', workerId ? 'Worker job priority' : 'Job priority');
  row.append(el('span', 'hint', workerId ? 'Worker priority' : 'Job priority'), input, button('Set priority', () => {
    if (!input.checkValidity() || input.value === '') { input.reportValidity(); return; }
    control({ op: 'priority', id: task.id, priority: Number(input.value), ...(workerId ? { worker: workerId } : {}) });
  }, !writable())); return row;
}
function showInspection() {
  if (!inspecting || !snapshot) return;
  const { kind, id } = inspecting; const content = $('inspect-content'); content.replaceChildren();
  content.append(button('Refresh inspection ↻', async () => { await refresh(); showInspection(); }), el('p', 'hint', 'Snapshot ' + new Date(refreshedAt || Date.now()).toLocaleTimeString() + '. Uncertain resources are not cleared by opening this view.'));
  if (kind === 'stash') {
    const db=stashDetails.get(id); $('inspect-title').textContent=db?.name || 'Stash'; if(!db)return;
    content.append(el('p','hint',db.scope+' · observations can be stale; scan again before withdrawing'));
    for(const container of Object.values(db.containers || {})) {
      const row=el('article','inspection-row');row.append(el('h3','',container.x+', '+container.y+', '+container.z+' · '+container.block),badge(container.status,container.status==='observed'?'good':'bad'),el('p','hint',container.reason || 'Server contents observed '+new Date(container.observedAt).toLocaleString()),jsonDetails('Items and shulker classification',container));content.append(row);
    }
    return;
  }
  if (kind === 'job') {
    const task = [...snapshot.tasks, ...snapshot.history].find(t => t.id === id); $('inspect-title').textContent = task?.name || 'Job no longer present'; if (!task) return;
    content.append(el('p', 'muted', task.server + ' · ' + task.dimension), jobActions(task)); if (!terminal(task.status)) content.append(priorityControls(task));
    for (const [workerId, run] of Object.entries(task.runs || {})) {
      const worker = snapshot.workers.find(w => w.id === workerId); const row = el('article', 'inspection-row'); const header = el('div', 'card-header');
      header.append(el('h3', '', worker?.name || workerId), badge(run.status, tone(run.status))); row.append(header, el('p', '', run.detail || 'No reported detail'));
      if (run.requestedStatus) row.append(el('p', 'hint', 'Pending cleanup target: ' + run.requestedStatus));
      if (run.stashScan) row.append(stashPanel(run));
      if (!terminal(task.status)) row.append(priorityControls(task, workerId)); content.append(row);
    }
    if (snapshot.highways[task.crew]?.execution) content.append(jsonDetails('Crew verification, supply reservations and exchange state', snapshot.highways[task.crew]));
    content.append(jsonDetails('Full job snapshot', task));
  } else if (kind === 'crew') {
    const native = snapshot.highways[id] || {}; $('inspect-title').textContent = snapshot.crewLabels?.[id]||id;
    content.append(resourceLine(native.resourceCounts,true),predictionLine(native.roadPrediction,id));chatPanel(content,id);managementControls(content,id);crewControls(content,id);
    content.append(workerTable(snapshot.workers.filter(w => w.crew === id)));
    for(const task of snapshot.tasks.filter(t=>t.crew===id))for(const run of Object.values(task.runs || {}))if(run.stashScan)content.append(stashPanel(run));
    if (native.execution) content.append(button('End native highway', () => control({ op: 'end-highway', crew: id }, 'End this crew’s native highway and cancel its owning job? Outstanding supplies remain recorded.'), !writable(), 'danger'));
    content.append(jsonDetails('Supply ownership, verification and diagnostics', native), eventList((native.events || []).slice(-20).reverse()));
  } else {
    const worker = snapshot.workers.find(w => w.id === id); $('inspect-title').textContent = worker?.name || 'Worker no longer connected'; if (!worker) return;
    const state = workerState(worker); content.append(el('p', 'job-detail', state.detail), el('p', 'hint', worker.id));
    if(state.run?.stashScan)content.append(stashPanel(state.run));
    content.append(resourceLine(state.native?.resourceCounts),predictionLine(state.native?.roadPrediction,worker.crew,worker.id));chatPanel(content,worker.crew,worker.id);managementControls(content,worker.crew,worker.id);crewControls(content,worker.crew,worker.id);
    content.append(jsonDetails('Worker diagnostics and current crew report', { ...worker, crewReport: state.native || null }));
  }
  updateChat();
}
function stashPanel(run) {
  const panel=el('section','management-section');panel.dataset.stashRun=run.id;fillStashPanel(panel,run);return panel;
}
function fillStashPanel(panel,run) {
  const s=run.stashScan;panel.replaceChildren();if(!s)return;
  panel.append(el('h3','','Stash scan · '+s.name),badge(s.phase,tone(run.status)),el('p','',s.reason),el('p','hint',number(s.observed)+' observed · '+number(s.inferred||0)+' inferred · '+number(s.unscanned)+' unscanned · '+number(s.missingChunks)+' missing chunks · discovery '+number(s.discovery)+' / '+number(s.volume)));
  const age=Math.max(0,Date.now()-(s.receivedAt || 0));panel.append(el('p',age>5000?'error':'hint','Telemetry '+Math.round(age/1000)+'s old · target '+(s.target || '—')+' · movement '+(s.movementTarget || '—')),el('p','hint','Last action: '+s.lastAction+' · '+Math.round(Math.max(0,Date.now()-s.lastProgressAt)/1000)+'s ago · '+number(s.attempts)+' opening attempts'),el('p','hint','Runtime: '+run.detail));
  const events=el('details');events.append(el('summary','','Diagnostic timeline · last '+(run.stashEvents || []).length+' state changes'));
  for(const event of [...(run.stashEvents || [])].reverse())events.append(el('p','hint',new Date(event.receivedAt).toLocaleTimeString()+' · '+event.phase+' · '+event.reason+' · '+(event.target || '—')+' · '+event.runtimeDetail));panel.append(events);
}
function refreshStashPanels() {
  for(const panel of document.querySelectorAll('[data-stash-run]')){
    const run=[...snapshot.tasks,...snapshot.history].flatMap(t=>Object.values(t.runs || {})).find(r=>r.id===panel.dataset.stashRun);if(run)fillStashPanel(panel,run);
  }
}
function stashCard(stash) {
  const card=el('article','job-card');card.append(el('h3','',stash.name),el('p','hint',(snapshot.crewLabels?.[stash.crew] || stash.crew)+' · '+stash.scope),el('p','',number(stash.observed)+' observed · '+number(stash.inferred||0)+' inferred · '+number(stash.unscanned)+' unscanned'),el('p','hint','Last observation '+new Date(stash.updatedAt).toLocaleString()));
  const table=el('table','worker-table');for(const [item,count] of Object.entries(stash.items || {}).sort((a,b)=>b[1]-a[1])){const row=el('tr');row.append(el('td','',item),el('td','',number(count)));table.append(row);}card.append(table);
  const actions=el('div','actions');actions.append(button('Scan with workers',()=>{
    $('new-job').click();$('job-kind').value='stash';$('job-crew').value=stash.crew;updateWorkers();updateSource();$('job-name').value='Inspect '+stash.name;
    $('job-args').value=JSON.stringify(stash.bounds);const [server,dimension]=stash.scope.split('\n');$('job-server').value=server;$('job-dimension').value=dimension;
  },!writable()),button('Inspect containers',async()=>{try{const db=await api('control',{op:'stash-get',crew:stash.crew,scope:stash.scope,name:stash.name});const key=JSON.stringify([stash.crew,stash.scope,stash.name]);stashDetails.set(key,db);while(stashDetails.size>16)stashDetails.delete(stashDetails.keys().next().value);inspecting={kind:'stash',id:key};showInspection();$('inspect-dialog').showModal();}catch(e){notice(e.message);}}));card.append(actions);return card;
}
function overallResources(stashes) {
  const unique=new Map();for(const stash of stashes){const key=JSON.stringify([stash.scope,stash.name,stash.bounds]);if(!unique.has(key)||(unique.get(key).updatedAt||0)<(stash.updatedAt||0))unique.set(key,stash);}
  const totals={};for(const stash of unique.values())for(const [item,count] of Object.entries(stash.items||{}))totals[item]=(totals[item]||0)+count;
  const card=el('article','job-card');const table=el('table','worker-table');for(const [item,count] of Object.entries(totals).sort((a,b)=>b[1]-a[1])){const row=el('tr');row.append(el('td','',item),el('td','',number(count)));table.append(row);}
  card.append(table.childElementCount?table:el('p','hint','No observed resources yet. Export a stash definition, then assign a scan.'));return card;
}

$('new-job').addEventListener('click', () => {
  if (!writable()) return;
  $('job-error').textContent = pendingSubmission ? 'Submission outcome is uncertain. Retry the same request or inspect Jobs; editing is locked to avoid duplicate work.' : '';
  $('job-fields').disabled = !!pendingSubmission; $('job-submit').textContent = pendingSubmission ? 'Retry same submission ↗' : 'Dispatch job ↗';
  if (!pendingSubmission) {
    $('job-crew').replaceChildren(); snapshot.crews.forEach(crew => { const option = el('option', '', crew); option.value = crew; $('job-crew').append(option); });
    updateWorkers(); updateSource();
  }
  $('job-dialog').showModal();
});
function updateWorkers() {
  $('job-workers').replaceChildren(); const crew = $('job-crew').value;
  const workers = snapshot.workers.filter(w => w.crew === crew && w.connected && w.reconciled);
  for (const worker of workers) {
    const label = el('label', 'worker-option'); const checkbox = el('input'); checkbox.type = 'checkbox'; checkbox.value = worker.id; checkbox.checked = true; checkbox.addEventListener('change', updateScope);
    const text = el('span', '', worker.name); text.append(el('small', '', ' · ' + workerState(worker).status)); label.append(checkbox, text); $('job-workers').append(label);
  }
  if (!workers.length) $('job-workers').append(el('p', 'hint', 'No connected, reconciled workers in this crew.')); updateScope();
}
function selectedWorkers() { return [...$('job-workers').querySelectorAll('input:checked')].map(n => snapshot.workers.find(w => w.id === n.value)).filter(Boolean); }
function useWorkerPosition() {
  const worker=selectedWorkers()[0];if(!worker||!worker.positionFresh||!['x','y','z'].every(axis=>Number.isFinite(worker[axis])))return false;
  for(const axis of ['x','y','z'])$('highway-'+axis).value=Math.floor(worker[axis]);return true;
}
function updateScope() { const worker = selectedWorkers()[0]; if (worker) { const [server, dimension] = worker.scope.split('\n'); $('job-server').value = server; $('job-dimension').value = dimension; if($('job-kind').value==='highway')useWorkerPosition(); } }
function updateSource() {
  const kind = $('job-kind').value; $('highway-field').hidden=kind!=='highway';$('package-field').hidden = kind !== 'package'; $('lua-field').hidden = kind !== 'lua';$('args-field').hidden=kind==='highway';
  if (!$('job-script').value) $('job-script').value = WAIT;
  if(kind==='highway') { if(!$('job-name').value||['Highway job','Inspect stash'].includes($('job-name').value))$('job-name').value='6b6t highway';useWorkerPosition(); }
  if(kind==='stash') { $('job-name').value='Inspect stash'; if($('job-args').value==='{}')$('job-args').value=JSON.stringify({name:'Main stash',minX:0,maxX:15,minY:116,maxY:120,minZ:0,maxZ:15}); }
}
$('job-crew').addEventListener('change', updateWorkers); $('job-kind').addEventListener('change', updateSource);
$('highway-worker-position').addEventListener('click',()=>{if(!useWorkerPosition())$('job-error').textContent='The first selected worker does not have a fresh in-world position.';});
$('package-file').addEventListener('change', async () => {
  packaged = null; $('package-summary').textContent = ''; $('job-error').textContent = '';
  const file = $('package-file').files[0]; if (!file) return;
  try {
    if (file.size > 4 * 1024 * 1024) throw new Error('Workflow packages must fit within 4 MiB.');
    const data = JSON.parse(await file.text()); if (data.version !== 1 || !data.programs?.[data.entry]) throw new Error('Choose an exported workflow package, not a host dispatch or job record.');
    packaged = data; const name = data.programs[data.entry].name || data.entry; $('job-name').value = name;
    $('package-summary').textContent = name + ' · ' + Object.keys(data.profiles || {}).length + ' captured profiles' + (data.geometry ? ' · captured highway geometry' : '');
  } catch (error) { $('job-error').textContent = error.message; }
});
$('job-form').addEventListener('submit', async event => {
  event.preventDefault(); if (!writable()) return; $('job-error').textContent = '';
  try {
    if (!pendingSubmission) {
      const workers = selectedWorkers(); if (!workers.length) throw new Error('Select at least one connected worker.');
      const scope = $('job-server').value.trim() + '\n' + $('job-dimension').value.trim();
      if (workers.some(w => w.scope !== scope)) throw new Error('Selected workers must be on the specified server and dimension.');
      const kind = $('job-kind').value; if (kind === 'package' && !packaged) throw new Error('Choose an exported workflow package first.');
      const common={id:crypto.randomUUID(),name:$('job-name').value.trim(),crew:$('job-crew').value,workers:workers.map(w=>w.id),server:$('job-server').value.trim(),dimension:$('job-dimension').value.trim(),priority:Number($('job-priority').value)};
      if(kind==='highway') {
        if(workers.length>5)throw new Error('Native highway jobs support at most five workers.');
        pendingSubmission={op:'submit-highway',...common,direction:$('highway-direction').value,length:Number($('highway-length').value),x:Number($('highway-x').value),y:Number($('highway-y').value),z:Number($('highway-z').value)};
      } else {
        const args = JSON.parse($('job-args').value); if (!args || Array.isArray(args) || typeof args !== 'object') throw new Error('Workflow arguments must be a JSON object.');
        pendingSubmission = {op:'submit',...common,args,...(kind === 'package' ? { package: packaged } : { script: kind === 'stash' ? STASH_SCAN : kind === 'wait' ? WAIT : $('job-script').value })};
      }
    }
    busy = true; $('job-fields').disabled = true; $('job-submit').disabled = true; render();
    await api('control', pendingSubmission); pendingSubmission = null; $('job-dialog').close(); notice('Job dispatched. Follow delivery and worker readiness in Jobs.', true);
  } catch (error) {
    const uncertain = pendingSubmission && error.uncertain;
    if (!uncertain) pendingSubmission = null;
    $('job-fields').disabled = !!pendingSubmission; $('job-submit').textContent = pendingSubmission ? 'Retry same submission ↗' : 'Dispatch job ↗';
    $('job-error').textContent = error.message + (pendingSubmission ? ' Outcome uncertain: retry uses the same submission ID. Inspect Jobs before creating replacement work.' : '');
  } finally { busy = false; $('job-submit').disabled = false; await refresh(); }
});

$('demo').addEventListener('click', () => {
  generation++;
  token = ''; demo = true; const now = Date.now();
  const workers = ['Atlas', 'AtlasBot', 'AtlasBot2'].map((name, i) => ({ id: 'demo-worker-' + i, name, crew: 'Highway', scope: 'example.invalid\nminecraft:the_nether', connected: true, reconciled: true, positionFresh: true, observationAgeMs: 120, x: i - 1, y: 116, z: -2048, current: 'demo-run-' + i }));
  const nativeWorkers = workers.map((w, i) => ({ ...w, phase: i === 2 ? 'resupplying' : 'building', status: i === 2 ? 'Recovering supply container; crew continues' : 'Healthy · paving and excavating', currentRow: 2048, verifiedBase: 2049, verifiedMask: 31, currentResolved: true, fresh: true, resourceCounts: { inventory: { obsidian: 512-i*64, pickaxes: 3, food: 48 }, enderChest: { obsidian: 1728, pickaxes: 9, food: 128 }, total: { obsidian: 2240-i*64, pickaxes: 12, food: 176 }, enderChestKnown: true } }));
  const task = { id: 'demo-job', name: 'Northbound · the long road', crew: 'Highway', workflowName: 'Highway Builder', status: 'Running', priority: 0, server: 'example.invalid', dimension: 'minecraft:the_nether', highwayProgress: 2048, nativeDefinition: { length: 100000 }, detail: 'Paving verified. Detached supplier returning; remaining workers keep building.', runs: Object.fromEntries(workers.map((w, i) => [w.id, { id: w.current, status: 'Running', detail: nativeWorkers[i].status }])) };
  snapshot = { status: 'Running', crews: ['Highway', 'Survey'], workers, tasks: [task], history: [], highways: { Highway: { phase: 'building', progress: 2048, length: 100000, execution: 'demo-execution', supplyOwner: workers[2].id, workers: nativeWorkers, resourceCounts: { total: { obsidian: 6528, pickaxes: 36, food: 528 }, workers: 3, enderChestKnown: true }, events: [{ time: now - 20000, detail: 'Worker detached for supplies. Remaining builders share the road.' }, { time: now - 5000, detail: 'Verified forward work window advanced.' }] }, Survey: { phase: 'idle', events: [] } } };
  refreshedAt = now; page = 'overview'; unlock();
});
