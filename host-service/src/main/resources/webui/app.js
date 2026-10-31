'use strict';
const $ = id => document.getElementById(id);
const number = value => Number(value || 0).toLocaleString();
const terminal = status => ['Complete', 'Failed', 'Cancelled'].includes(status);
const headings = {
  overview: ['THE BIG PICTURE', 'Operations overview', 'A live pulse on your workers and the work ahead.'],
  crews: ['TEAM OPERATIONS', 'Your crews', 'Separate teams, separate work. One shared control room.'],
  workers: ['THE ROSTER', 'Your workers', 'Who is here, where they are, and what is holding them up.'],
  hostiles: ['THE RECKONING', 'Known hostile players', 'Server-scoped protection shared by this host. Crystal suspects are never added automatically.'],
  incidents: ['THE RECKONING', 'Incident timeline', 'Player sightings, damage, deaths and safety reconnects. Observations are evidence, not guaranteed attribution.'],
  servers: ['CONNECTION IDENTITIES', 'Minecraft servers', 'Explicit alternate addresses for the same world.'],
  notifications: ['OWNER ALERTS', 'Discord notifications', 'Private owner DMs. Notification delivery never controls or delays a worker.'],
  bank: ['KIT CREDITS', 'Credit bank', 'Earned credits and the free hourly kit have separate accounting.'],
  jobs: ['THE WORK AHEAD', 'Active jobs', 'Host decisions are authoritative—even when workers are offline.'],
  presets: ['REUSABLE WORK', 'Job presets', 'Built-in starting points and your own defaults. Running jobs keep their captured settings.'],
  stashes: ['RESOURCE INTELLIGENCE', 'Your stashes', 'Observed contents, not guaranteed stock. Unscanned is never empty.'],
  kits: ['RESOURCE INTELLIGENCE', 'All kits', 'Contents and locations across your stash catalogs. Deliver without the job creation wizard.'],
  history: ['OFF THE WORKBENCH', 'Job history', 'Finished work stays here. Cleanup receipts are never silently erased.']
};
let token = '', demo = false, snapshot = null, page = 'overview', refreshedAt = 0, refreshing = false, polling;
let busy = false, packaged = null, pendingSubmission = null, pendingSubmissionKey = null, inspecting = null;
let scanStash = null, kitContext = null;
let kitFilter = '', kitDelivery = null, pendingKitDelivery = null;
let selectedStash = '', stashTab = 'columns', stashFilter = '', kitStashFilter = '', selectedColumn = '';
let columnScale = 'fit';
const hostileDraft={server:'',name:'',uuid:''};
const incidentView={before:'',kind:'',search:'',data:null,error:'',loading:false};
let safetyDraft=null;
let discordDraft=null;
let serverDraft=null;
let selectedColumns = new Set();
const stashReads = new Map(), stashLayouts = new Map();
const stashSortReads = new Map();
let presetList=[],reviewedSubmission=null,lastLaunch=null,inspectionKey='';
let generation = 0;
const tokenKey = 'monocle-operator-token';
try { token = sessionStorage.getItem(tokenKey) || ''; } catch (_) { /* Storage may be disabled. */ }
function saveToken() { try { sessionStorage.setItem(tokenKey, token); } catch (_) { /* Keep this tab usable without storage. */ } }
function clearToken() { bankData=null;bankError='';token = ''; try { sessionStorage.removeItem(tokenKey); } catch (_) { /* Storage may be disabled. */ } }
const WAIT = 'return function(ctx)\n if ctx.state.waited then return bot.done() end\n ctx.state.waited=true\n return bot.wait(200)\nend';
const STASH_SCAN = 'return function(ctx)\n if ctx.state.started then return bot.done(ctx.result) end\n ctx.state.started=true\n return bot.stash_scan(ctx.args)\nend';

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
let bankData=null,bankLoading=false,bankError='';
async function loadBank(){
  if(bankLoading||demo)return;bankLoading=true;const epoch=generation;
  try{const [accounts,keys,kitbot]=await Promise.all([resource('GET','balances'),resource('GET','balance-api-keys'),api('control',{op:'kitbot-get'})]);if(epoch!==generation)return;bankData={accounts:accounts.items,keys:keys.items,reviews:Object.values(kitbot.requests||{}).filter(r=>r.payment==='Credits'&&r.creditsCharged&&r.creditSettlement!=='Settled'&&terminal(r.status)).map(r=>({id:r.id,name:r.name,count:r.count,status:r.status}))};bankError='';}
  catch(error){if(epoch===generation)bankError=error.message;}
  finally{bankLoading=false;if(epoch===generation&&page==='bank')render();}
}
function bankDialog(title){const dialog=el('dialog'),body=el('div','management-section');body.append(el('h2','',title));dialog.append(body);document.body.append(dialog);dialog.addEventListener('close',()=>dialog.remove());dialog.showModal();return {dialog,body};}
function renderBank(){
  const root=$('content');root.append(el('p','hint','Accounts are created only after receiving credits. Reading an unknown account returns zero. Holding credits gives a ten-minute free cooldown; free claims never spend credits.'));
  if(demo){root.append(el('p','hint','Connect to a live host with an administrative API key to manage the bank.'));return;}
  const actions=el('div','actions');actions.append(button('Gift or revoke credits',()=>bankAdjustment(),!writable()),button('Generate balance API key',()=>bankKey(),!writable()),button('Reload bank',()=>{bankData=null;bankError='';loadBank();},bankLoading||demo));root.append(actions);
  if(!bankData){root.append(el('p',bankError?'error':'hint',bankError||'Loading bank…'));if(!bankLoading&&!bankError&&!demo)loadBank();return;}
  if(bankData.reviews.length){const reviews=el('div','management-section');reviews.append(el('h3','','Charges needing review'),el('p','hint','The worker could not prove the final delivery count. Inspect the outcome before confirming a refund.'));for(const order of bankData.reviews){const row=el('article','card');row.append(el('strong','',order.name+' · '+order.count+' charged credits'),el('p','hint',order.status+' · '+order.id),button('Resolve charged order',()=>bankReview(order),!writable()));reviews.append(row);}root.append(reviews);}
  const accounts=el('div','management-section');accounts.append(el('h3','','Accounts (most recently updated)'));
  if(!bankData.accounts.length)accounts.append(el('p','hint','No funded accounts yet. Everyone else has zero credits.'));
  for(const account of bankData.accounts){const row=el('article','card');row.append(el('strong','',account.name),el('p','',number(account.balance)+' credits'),el('p','hint',account.playerId),button('Transactions',async()=>{const {dialog,body}=bankDialog(account.name+' · credit history');body.append(button('Close',()=>dialog.close()));try{const history=await resource('GET','balances/'+account.playerId+'/transactions');for(const transaction of history.items)body.append(el('p','hint',new Date(transaction.updatedAt).toLocaleString()+' · '+(transaction.delta>0?'+':'')+transaction.delta+' → '+transaction.balance+' · '+transaction.reason));if(!history.items.length)body.append(el('p','hint','No transactions.'));}catch(error){body.append(el('p','error',error.message));}},!writable()));accounts.append(row);}root.append(accounts);
  const keys=el('div','management-section');keys.append(el('h3','','Balance API keys'),el('p','hint','Read/write permissions apply only to /v1/balances. These keys cannot access jobs, stashes, host control, player lookup, or key administration. Tokens are shown only once.'));
  for(const key of bankData.keys){const row=el('article','card');row.append(el('strong','',key.name),el('p','hint',key.scopes.join(', ')+' · '+(key.revoked?'Revoked':'Active')));if(!key.revoked)row.append(button('Revoke key',async()=>{try{await resource('DELETE','balance-api-keys/'+key.id);await loadBank();}catch(error){notice(error.message);}},!writable()));keys.append(row);}root.append(keys);
}
function bankKey(){
  const {dialog,body}=bankDialog('Generate balance API key'),form=el('form'),name=el('input'),permissions=el('select'),error=el('p','error');permissions.setAttribute('aria-label','Permissions');name.required=true;name.maxLength=64;
  for(const [value,label] of [['read','Balances: read only'],['write','Balances: write only'],['both','Balances: read and write']]){const option=el('option','',label);option.value=value;permissions.append(option);}
  for(const [title,input] of [['Key name',name],['Permissions',permissions]]){const label=el('label','',title);label.append(input);form.append(label);}
  const submit=el('button','primary','Generate key');submit.type='submit';form.append(error,submit);body.append(form,button('Close',()=>dialog.close()));const id=crypto.randomUUID();let payload;
  form.addEventListener('submit',async event=>{event.preventDefault();submit.disabled=true;error.textContent='';try{if(!name.value.trim())throw Error('Enter a key name.');payload??={name:name.value.trim(),scopes:permissions.value==='read'?['balances:read']:permissions.value==='write'?['balances:write']:['balances:read','balances:write']};name.disabled=permissions.disabled=true;const key=await resource('POST','balance-api-keys',payload,id);form.remove();if(key.token){const label=el('label','','Copy this token now. It cannot be retrieved later.'),secret=el('input');secret.readOnly=true;secret.value=key.token;label.append(secret);body.append(label,button('Copy token',()=>navigator.clipboard.writeText(secret.value),!navigator.clipboard));}else body.append(el('p','hint','This key was already created, but its secret cannot be retrieved. Revoke it and create another if you did not receive the token.'));await loadBank();}catch(failure){error.textContent=failure.message;submit.disabled=false;}});
}
function bankAdjustment(){
  const {dialog,body}=bankDialog('Adjust kit credits'),form=el('form'),name=el('input'),direction=el('select'),amount=el('input'),reason=el('input'),error=el('p','error');direction.setAttribute('aria-label','Operation');name.required=true;name.pattern='[A-Za-z0-9_]{1,16}';name.maxLength=16;amount.type='number';amount.min=1;amount.max=1000000;amount.step=1;amount.required=true;reason.required=true;reason.maxLength=256;
  for(const [value,label] of [['credit','Gift credits'],['debit','Revoke credits']]){const option=el('option','',label);option.value=value;direction.append(option);}
  for(const [title,input] of [['Player username',name],['Operation',direction],['Credit amount',amount],['Reason',reason]]){const label=el('label','',title);label.append(input);form.append(label);}
  const submit=el('button','primary','Apply credit adjustment');submit.type='submit';form.append(error,submit);body.append(form,button('Close',()=>dialog.close()));const id=crypto.randomUUID();let pending;
  form.addEventListener('submit',async event=>{event.preventDefault();submit.disabled=true;error.textContent='';try{if(!pending){if(!reason.value.trim())throw Error('Enter a reason.');const identity=await resource('GET','players/'+encodeURIComponent(name.value.trim()));pending={player:identity.id,body:{name:identity.name,direction:direction.value,amount:Number(amount.value),reason:reason.value.trim()}};}for(const input of [name,direction,amount,reason])input.disabled=true;const receipt=await resource('POST','balances/'+pending.player+'/transactions',pending.body,id);dialog.close();await loadBank();notice(receipt.name+' now has '+number(receipt.balance)+' credits.',true);}catch(failure){error.textContent=failure.message;submit.disabled=false;}});
}
function bankReview(order){
  const {dialog,body}=bankDialog('Resolve charged delivery'),form=el('form'),delivered=el('input'),reason=el('input'),error=el('p','error');delivered.type='number';delivered.min=0;delivered.max=order.count;delivered.step=1;delivered.required=true;reason.required=true;reason.maxLength=160;
  body.append(el('p','hint','Confirm how many of the '+order.count+' kits actually reached the recipient. Only the remainder will be refunded. This final settlement is audited and cannot be silently changed.'));
  for(const [title,input] of [['Confirmed kits delivered',delivered],['Review reason',reason]]){const label=el('label','',title);label.append(input);form.append(label);}
  const submit=el('button','primary','Confirm settlement');submit.type='submit';form.append(error,submit);body.append(form,button('Close',()=>dialog.close()));let pending;
  form.addEventListener('submit',async event=>{event.preventDefault();submit.disabled=true;try{if(!reason.value.trim())throw Error('Enter a review reason.');pending??={op:'kit-credit-settle',id:order.id,delivered:Number(delivered.value),reason:reason.value.trim()};delivered.disabled=reason.disabled=true;await api('control',pending);dialog.close();await loadBank();notice('Credit settlement recorded.',true);}catch(failure){error.textContent=failure.message;submit.disabled=false;}});
}
function notice(message, success = false) { $('notice').textContent = message; $('notice').className = 'banner' + (success ? ' success' : ''); $('notice').hidden = !message; }
async function api(path, request, method = request ? 'POST' : 'GET', extraHeaders = {}) {
  const attempt = generation;
  let response, body;
  try {
  response = await fetch('/ui/api/' + path, {
    method, credentials: 'omit', mode: 'cors', cache: 'no-store', signal: AbortSignal.timeout(8000),
    headers: { Authorization: 'Bearer ' + token, ...(request ? { 'Content-Type': 'application/json' } : {}), ...extraHeaders },
    ...(request ? { body: JSON.stringify(request) } : {})
  });
  body = await response.json();
  } catch (error) {
    const failure = new Error('Could not confirm the host response: ' + error.message);
    failure.uncertain = true; throw failure;
  }
  if (!response.ok) {
    if (response.status === 403) {
      if (path === 'status' && body.error === 'Invalid API token' && attempt === generation) clearToken();
      throw new Error((body.error || body.detail || 'Access rejected') + (path === 'status' ? '. Reconnect with the API token from an allowed origin.' : '. Your saved token is unchanged.'));
    }
    throw new Error(body.detail || body.error || 'Request rejected (HTTP ' + response.status + ')');
  }
  return body;
}
async function resource(method, path, body, commandId, revision) {
  const headers=method === 'GET' ? {} : { 'Idempotency-Key': commandId || crypto.randomUUID() };
  if(revision)headers['If-Match']=revision;
  const reply=await api('v1/' + path, body, method, headers);
  if(reply.state==='outcome_uncertain') {const error=new Error('The host cannot confirm this command yet. Inspect its operation receipt before creating replacement work.');error.uncertain=true;throw error;}
  return reply;
}
async function crewResource(legacyId) {
  const crews=await resource('GET','crews?limit=100');
  const match=crews.items.find(crew=>crew.name===(snapshot.crewLabels?.[legacyId]||legacyId));
  if(!match)throw new Error('Crew changed. Refresh the roster before retrying.');
  return resource('GET','crews/'+match.id);
}
async function stashResourceId(stash) {
  let cursor='';
  do {
    const page=await resource('GET','stashes?limit=100'+(cursor?'&cursor='+encodeURIComponent(cursor):''));
    const match=page.items.find(row=>row.crew===stash.crew&&row.scope===stash.scope&&row.name===stash.name);
    if(match)return match.id;
    cursor=page.nextCursor||'';
  } while(cursor);
  throw new Error('Stash changed. Refresh the stash list before retrying.');
}
async function refresh() {
  if (refreshing || !token && !demo) return;
  if (demo) { refreshedAt = Date.now(); render(); return; }
  refreshing = true;
  const attempt = generation;
  try {
    const data = await api('status'); if (attempt !== generation) return;
    snapshot = data; refreshedAt = Date.now(); render(); refreshStashPanels();
    if(page==='incidents')loadIncidents();
    if (pendingSubmission && [...snapshot.tasks, ...snapshot.history].some(t => t.id === pendingSubmission.id)) {
      pendingSubmission = pendingSubmissionKey = null; $('job-dialog').close(); notice('Job accepted by the host.', true);
    }
    if (pendingKitDelivery && [...snapshot.tasks, ...snapshot.history].some(t => t.id === pendingKitDelivery.body.id)) kitDeliveryAccepted();
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
  discordDraft=null;
  if (pendingKitDelivery && !confirm('A kit delivery may have reached the host. Disconnect anyway? Inspect Jobs before submitting another delivery.')) return;
  if (pendingSubmission && !confirm('A submission may have reached the host. Disconnect anyway? Inspect Jobs before submitting replacement work.')) return;
  clearToken(); demo = false; snapshot = null; packaged = null; pendingSubmission = pendingSubmissionKey = null; inspecting = null; refreshedAt = 0;
  generation++;
  incidentView.before=incidentView.kind=incidentView.search=incidentView.error='';incidentView.data=null;safetyDraft=null;
  kitDelivery = pendingKitDelivery = null;
  stashReads.clear(); stashSortReads.clear(); selectedStash = selectedColumn = ''; selectedColumns.clear(); stashTab = 'columns';
  clearTimeout(polling); document.querySelectorAll('dialog[open]').forEach(d => d.close());
  $('token').value = ''; $('app').hidden = true; $('login').hidden = false; $('login-error').textContent = ''; notice('');
}
$('login-form').addEventListener('submit', async event => {
  event.preventDefault(); demo = false; token = $('token').value.trim(); $('token').value = '';
  const attempt = ++generation;
  const submit = event.submitter; submit.disabled = true; $('login-error').textContent = '';
  try { const data = await api('status'); if (attempt !== generation) return; snapshot = data; refreshedAt = Date.now(); saveToken(); unlock(); }
  catch (error) { if (attempt === generation) { clearToken(); $('login-error').textContent = error.message; } }
  finally { submit.disabled = false; }
});
$('logout').addEventListener('click', logout);
if (token) api('status').then(data => { snapshot = data; refreshedAt = Date.now(); unlock(); }).catch(error => { $('login-error').textContent = error.message; });
$('refresh').addEventListener('click', refresh);
document.addEventListener('visibilitychange', () => { if (!document.hidden && (token || demo)) { refresh(); startPolling(); } });
function navigate(next,stash='') { location.hash=next+(stash?'/'+encodeURIComponent(stash):'');readRoute(); }
function readRoute() {
  const [next,...parts]=location.hash.slice(1).split('/');
  if(!headings[next])return;
  let stash='';try{if(next==='stashes'&&parts.length)stash=decodeURIComponent(parts.join('/'));}catch(_){/* Invalid links stay in the directory. */}
  if(stash!==selectedStash){stashTab='columns';selectedColumn='';selectedColumns.clear();}
  page=next;selectedStash=stash;render();if(page==='incidents')loadIncidents();
}
window.addEventListener('hashchange',readRoute);readRoute();
document.querySelectorAll('[data-page]').forEach(node => node.addEventListener('click', () => navigate(node.dataset.page)));
document.querySelectorAll('.close-dialog').forEach(node => node.addEventListener('click', () => node.closest('dialog').close()));
function columnTooltip(cell,x,y) {
  let tip=$('column-tooltip');
  if(!tip){tip=el('div','column-tooltip');tip.id='column-tooltip';tip.setAttribute('role','tooltip');document.body.append(tip);}
  if(!cell){tip.hidden=true;return;}
  tip.textContent=cell.title;tip.hidden=false;
  tip.style.left=Math.max(8,Math.min(x+12,innerWidth-tip.offsetWidth-8))+'px';
  tip.style.top=Math.max(8,Math.min(y+16,innerHeight-tip.offsetHeight-8))+'px';
}
document.addEventListener('pointermove',event=>{if(event.pointerType!=='touch')columnTooltip(event.target.closest('.column-tile'),event.clientX,event.clientY);});
document.addEventListener('focusin',event=>{const cell=event.target.closest('.column-tile');if(cell){const rect=cell.getBoundingClientRect();columnTooltip(cell,rect.left,rect.bottom);}else if($('column-tooltip'))$('column-tooltip').hidden=true;});
document.addEventListener('click',()=>{if($('column-tooltip'))$('column-tooltip').hidden=true;});
document.addEventListener('scroll',()=>{if($('column-tooltip'))$('column-tooltip').hidden=true;},true);

function section(title, subtitle = '') {
  const header = el('div', 'section-heading'); header.append(el('h2', '', title), el('span', '', subtitle)); $('content').append(header);
}
function empty(title, detail) { const node = el('div', 'empty'); node.append(el('h3', '', title), el('p', '', detail)); return node; }
function workerState(worker) {
  const native = snapshot.highways[worker.crew]?.workers?.find(w => w.id === worker.id);
  const task = snapshot.tasks.find(t => Object.values(t.runs || {}).some(r => r.id === worker.current));
  const run = task?.runs?.[worker.id];
  return { native, task, run, status: !worker.connected ? 'Offline' : worker.serverAliasesReady===false ? 'Server aliases pending' : native?.phase || run?.status || (worker.reconciled ? 'Ready' : 'Reconciling'),
    detail: worker.serverAliasesReady===false ? 'Waiting for the server identity policy; alias support requires Monocle 0.15.48+.' : native?.status || run?.detail || (worker.current ? 'Execution ' + worker.current : 'Ready for assignment') };
}
function render() {
  if (!snapshot) return;
  if((page!=='stashes'||stashTab!=='columns')&&$('column-tooltip'))$('column-tooltip').hidden=true;
  for (const [crew, state] of Object.entries(snapshot.highways)) {
    if (!state.length) continue;
    sampleCurrentRate(crew, state.execution, state.progress, refreshedAt);
    const supplying = phase => /resuppl|returning from suppl/i.test(phase || '');
    rememberForecast(crew,'crew',state.roadPrediction,(state.workers || []).some(w=>supplying(w.phase)));
    for (const worker of state.workers || []) rememberForecast(crew,worker.id,worker.roadPrediction,supplying(worker.phase));
  }
  for (const crew of currentRates.keys()) if (!snapshot.highways[crew]?.length) currentRates.delete(crew);
  const [kicker, title, subtitle] = headings[page];
  $('breadcrumb').textContent = page === 'kits' ? 'Stashes / Kits' : page[0].toUpperCase() + page.slice(1); $('page-kicker').textContent = kicker;
  $('page-title').textContent = title; $('page-subtitle').textContent = subtitle;
  document.querySelectorAll('.sidebar [data-page]').forEach(node => { const active=node.dataset.page === page || node.dataset.page === 'stashes' && page === 'kits'; node.classList.toggle('active', active); node.setAttribute('aria-current', active ? 'page' : 'false'); });
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
  $('metrics').hidden = page === 'stashes' || page === 'kits' || page === 'bank';
  const focused = $('content').contains(document.activeElement) ? document.activeElement.dataset.key : null;
  const scrolls = new Map([...$('content').querySelectorAll('[data-scroll]')].map(n=>[n.dataset.scroll,[n.scrollLeft,n.scrollTop]]));
  const expanded = new Set([...$('content').querySelectorAll('details[open][data-key]')].map(n=>n.dataset.key));
  $('content').replaceChildren();
  if (page === 'stashes' || page === 'kits') {
    const tabs=el('nav','storage-tabs');tabs.setAttribute('aria-label','Stash sections');
    for(const [id,label] of [['stashes','Stashes'],['kits','Kits']]){const tab=button(label,()=>navigate(id),false,page===id?'active':'','storage-'+id);tab.dataset.page=id;tab.setAttribute('aria-current',page===id?'page':'false');tabs.append(tab);}
    $('content').append(tabs);
  }
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
  if(page==='hostiles')renderHostiles();
  if(page==='incidents')renderIncidents();
  if(page==='notifications')renderNotifications();
  if(page==='bank')renderBank();
  if(page==='servers')renderServers();
  if (page === 'jobs' || page === 'history') {
    const jobs = page === 'history' ? snapshot.history : snapshot.tasks;
    section(page === 'history' ? 'Finished jobs' : 'Job queue', jobs.length + ' records');
    jobs.forEach(task => $('content').append(jobCard(task)));
    if (!jobs.length) $('content').append(empty(page === 'history' ? 'A clean slate' : 'Nothing in the queue', page === 'history' ? 'Finished jobs appear here, not in your active work.' : 'Connect workers, then dispatch a workflow to get moving.'));
  }
  if(page==='presets')$('content').append(empty('Reusable job presets','6b6t Highway, Follow crewmate, Travel to coordinates and Wait. Duplicate built-ins to customize future jobs.'),button('Open preset library',presetLibrary,!writable()));
  if (page === 'stashes') {
    renderStashes();
  }
  if (page === 'kits') {
    $('content').append(button('Categories & whisper kitbot',kitBotSettings,!writable()));
    const filters=el('div','storage-filters'),scope=el('select');scope.setAttribute('aria-label','Filter kits by stash');scope.dataset.key='kit-stash-filter';scope.append(el('option','','All stashes'));scope.firstChild.value='';
    for(const stash of snapshot.stashes||[]){const option=el('option','',stash.name);option.value=stashKey(stash);scope.append(option);}scope.value=kitStashFilter;
    scope.addEventListener('change',()=>{kitStashFilter=scope.value;render();});
    const sources=(snapshot.stashes||[]).filter(s=>!kitStashFilter||stashKey(s)===kitStashFilter);
    const catalog=kitCatalog(sources), search=el('input');search.type='search';search.value=kitFilter;search.placeholder='Search kit names, contents or stash names';search.setAttribute('aria-label','Search kits');search.dataset.key='kit-search';
    search.addEventListener('input',()=>{kitFilter=search.value;render();});filters.append(search,scope);$('content').append(filters);
    const matching=catalog.filter(kit=>[kit.type.name,kit.type.item,...Object.keys(kit.type.items||{}),...kit.sources.map(s=>s.stash.name)].join(' ').toLowerCase().includes(kitFilter.toLowerCase()));
    section('Kit catalog',matching.length+' of '+catalog.length+' types · click a kit for contents and chest locations');
    const table=el('table','worker-table'),header=el('tr');for(const title of ['Kit','Stock','Stashes',''])header.append(el('th','',title));table.append(header);
    for(const kit of matching){const row=el('tr'),name=el('td'),label=shulkerLabel(row,kit.type.item);name.append(button(kit.type.name||label,()=>openKit(kit),false,'text-button'),el('small','',label));const actions=el('td');actions.append(button('Deliver',()=>openKit(kit,null,true),!writable()));row.append(name,el('td','',kitStockText(kit)),el('td','',kit.sources.map(s=>s.stash.name).join(', ')),actions);table.append(row);}
    const panel=el('div','table-panel');panel.append(table);$('content').append(matching.length?panel:empty('No matching kits','Scan or export a stash, or try another search.'));
    $('content').append(el('p','hint','Observed counts are not live availability. Global totals count overlapping container coordinates once per server/dimension, using the latest non-inferred record. Changed/unscanned chests are not empty. Same name/color with different exemplars stays separate.'));
    const usage=el('details');usage.dataset.key='kit-usage';usage.append(el('summary','','Kit delivery & transfer activity'),kitUsage(snapshot));$('content').append(usage);
  }
  if (page === 'overview') {
    const events = Object.entries(snapshot.highways).flatMap(([crew, h]) => (h.events || []).map(e => ({ ...e, crew }))).sort((a, b) => b.time - a.time).slice(0, 6);
    section('From the field', 'Latest crew events'); $('content').append(eventList(events));
    if (snapshot.lastConnectionError || snapshot.telemetryError) notice(snapshot.lastConnectionError || snapshot.telemetryError);
  }
  if (focused) $('content').querySelector('[data-key="' + CSS.escape(focused) + '"]')?.focus({ preventScroll: true });
  for(const node of $('content').querySelectorAll('[data-scroll]')){const position=scrolls.get(node.dataset.scroll);if(position){node.scrollLeft=position[0];node.scrollTop=position[1];}}
  for(const node of $('content').querySelectorAll('details[data-key]'))if(expanded.has(node.dataset.key))node.open=true;
  if ($('inspect-dialog').open) {if(inspecting?.shape!==inspectionShape())showInspection();updateChat();document.querySelectorAll('.managed-control').forEach(n=>{n.disabled=!writable();});document.querySelectorAll('.guided-settings').forEach(n=>n.updateStatus?.()); }
  document.querySelectorAll('.operator-live').forEach(n=>n.updateStatus?.());
  if($('kit-dialog').open)updateDeliveryWorkers();
}
function progress(node, value, total) {
  const percent = Math.max(0, Math.min(100, 100 * (value || 0) / Math.max(1, total || 1)));
  const info = el('div', 'card-progress'); info.append(el('strong', '', percent.toFixed(1) + '%'), el('span', '', number(value) + ' of ' + number(total) + ' blocks mined'));
  const bar = el('progress'); bar.max = Math.max(1, total || 1); bar.value = Math.min(value || 0, total || 1); bar.setAttribute('aria-label', 'Verified highway progress'); node.append(info, bar);
}
function renderNotifications(){
  const state=snapshot.discordNotifications;
  if(!state){$('content').append(empty('Host update required','Install 0.14.3 or later for Discord owner DMs.'));return;}
  const draft=discordDraft||{enabled:!!state.enabled,ownerId:state.ownerId||'',botToken:''};
  const form=el('form','card');form.append(el('h2','','Owner notification settings'));
  const label=el('label','worker-option'),enabled=el('input');enabled.type='checkbox';enabled.checked=draft.enabled;enabled.disabled=!writable();enabled.dataset.key='discord-enabled';
  enabled.addEventListener('change',()=>{discordDraft={...(discordDraft||draft),enabled:enabled.checked};});label.append(enabled,el('span','','Enable Discord DMs'));form.append(label);
  for(const [key,title,type,max] of [['ownerId','Owner Discord user ID','text',20],['botToken','Discord bot token · blank keeps saved token','password',512]]){
    const field=el('label','',title),input=el('input');input.type=type;input.value=draft[key];input.maxLength=max;input.disabled=!writable();input.dataset.key='discord-'+key;input.autocomplete=key==='botToken'?'new-password':'off';
    if(key==='ownerId')input.inputMode='numeric';input.addEventListener('input',()=>{discordDraft={...(discordDraft||draft),[key]:input.value};});field.append(input);form.append(field);
  }
  form.append(el('p','hint','Use a bot token, never a personal account token. Enable Developer Mode in Discord to copy your user ID. The bot must be able to DM you (normally share a server and allow DMs). Credentials stay on this host, never in worker settings or status responses. Use HTTPS when configuring remotely.'));
  const save=el('button','primary','Save settings');save.type='submit';save.disabled=!writable();form.append(save);
  form.addEventListener('submit',async event=>{
    event.preventDefault();if(!writable())return;busy=true;
    try{await api('control',{op:'discord-settings',...(discordDraft||draft)});discordDraft=null;notice('Discord settings saved. Send a test DM to verify delivery.',true);}
    catch(error){notice(error.message);}finally{busy=false;await refresh();}
  });
  form.append(button('Send test DM',()=>control({op:'discord-test'}),!writable()||!state.enabled||!state.configured),button('Forget token and disable',async()=>{
    if(!writable()||!confirm('Forget the saved Discord bot token and disable notifications?'))return;busy=true;
    try{await api('control',{op:'discord-settings',enabled:false,clearToken:true});discordDraft=null;notice('Discord token removed; notifications disabled.',true);}catch(error){notice(error.message);}finally{busy=false;await refresh();}
  },!writable()));$('content').append(form);
  section('Delivery status',state.enabled?(state.blocked?'Needs attention':'Enabled'):'Disabled');
  const status=el('article','card');status.append(el('p','',number(state.pending)+' pending · '+number(state.delivered)+' delivered this session · '+number(state.dropped)+' dropped this session'));
  if(state.lastDeliveredAt)status.append(el('p','hint','Last DM sent: '+new Date(state.lastDeliveredAt).toLocaleString()));
  if(state.retryAt>Date.now())status.append(el('p','hint','Next attempt after '+new Date(state.retryAt).toLocaleTimeString()));
  if(state.error)status.append(el('p','error',state.error));
  status.append(el('p','hint','Alerts: worker/job failures, inspection-required states, disconnects, deaths, host-wide hostile sightings, hostile safety logouts, completions and cancellations. Routine restocking, eating, neutral sightings and manual pauses do not send DMs. Repeated identical connection errors are deduplicated for five minutes.'));
  $('content').append(status);
}
function renderHostiles(){
  const catalog=snapshot.knownHostiles;
  if(!catalog){$('content').append(empty('Host update required','Install 0.14.1 or later to manage known hostile players.'));return;}
  const policy=el('article','card'),label=el('label','worker-option'),toggle=el('input');toggle.type='checkbox';toggle.checked=!!catalog.enabled;toggle.disabled=!writable();toggle.dataset.key='hostile-policy';
  toggle.addEventListener('change',()=>control({op:'hostile-policy',enabled:toggle.checked},toggle.checked?'Enable immediate logout when any updated worker sees a known hostile outside its detected lobby?':undefined));
  label.append(toggle,el('span','','Disconnect same-server workers when any worker sees a known hostile'));policy.append(label,el('p','hint','Host-wide warnings cross crew boundaries, even with the guard off. Updated workers cache the list for immediate local protection; detected lobbies never report sightings or combat incidents.'));
  for(const alarm of snapshot.hostileWarnings||[]){const p=alarm.sighting?.player||{};policy.append(el('p','error',`${p.name||'Hostile'} seen by ${alarm.observerName} · ${alarm.crew} · ${alarm.server} · ${p.x}, ${p.y}, ${p.z}`));}
  if(Object.hasOwn(catalog,'safeReconnect')){
    const draft=safetyDraft||{safeReconnect:!!catalog.safeReconnect,reconnectSeconds:catalog.reconnectSeconds||300};
    const retryLabel=el('label','worker-option'),retry=el('input');retry.type='checkbox';retry.checked=draft.safeReconnect;retry.disabled=!writable();retry.dataset.key='safety-retry';
    retry.addEventListener('change',()=>{safetyDraft={...(safetyDraft||draft),safeReconnect:retry.checked};});retryLabel.append(retry,el('span','','Retry safely after a hostile logout'));
    const delayLabel=el('label','','Minimum safety wait (seconds)'),delay=el('input');delay.type='number';delay.min=5;delay.max=3600;delay.value=draft.reconnectSeconds;delay.disabled=!writable();delay.dataset.key='safety-seconds';
    delay.addEventListener('input',()=>{safetyDraft={...(safetyDraft||draft),reconnectSeconds:Number(delay.value)};});delayLabel.append(delay);
    policy.append(retryLabel,delayLabel,button('Save reconnect policy',async()=>{const value=safetyDraft||draft;if(!Number.isInteger(value.reconnectSeconds)||value.reconnectSeconds<5||value.reconnectSeconds>3600){notice('Safety wait must be 5–3600 whole seconds.');return;}await control({op:'hostile-policy',enabled:catalog.enabled,...value});safetyDraft=null;},!writable()),el('p','hint','Opt-in; default wait is five minutes. Uses AutoReconnect, honors retry/error limits, and enables Lobby Skip for 6b6t. The wait cannot know whether an attacker left while you were offline. Reconnect Now bypasses it.'));
    const old=snapshot.workers.filter(w=>w.connected&&!w.safeReconnectSupported);if(catalog.safeReconnect&&old.length)policy.append(el('p','error','Safety reconnect unsupported: '+old.map(w=>w.name).join(', ')+' · update to 0.14.2 or later.'));
  }
  const unsupported=snapshot.workers.filter(w=>w.connected&&!w.knownHostilesSupported);if(unsupported.length)policy.append(el('p','error','Not protected by this policy: '+unsupported.map(w=>w.name).join(', ')+' · update these clients.'));
  const localOnly=snapshot.workers.filter(w=>w.connected&&w.knownHostilesSupported&&!w.hostileWarningsSupported);if(localOnly.length)policy.append(el('p','error','Local detection only; host-wide warnings unsupported: '+localOnly.map(w=>w.name).join(', ')+' · update to 0.14.4 or later.'));
  $('content').append(policy);
  if(!hostileDraft.server)hostileDraft.server=(snapshot.workers.find(w=>w.scope)?.scope||'').split('\n')[0];
  const form=el('form','card');form.append(el('h2','','Add a hostile player'));
  for(const [key,title,max] of [['server','Minecraft server address',255],['name','Hostile username',16],['uuid','Player UUID (optional)',36]]){
    const field=el('label','',title),input=el('input');input.type='text';input.value=hostileDraft[key];input.maxLength=max;input.required=key!=='uuid';input.disabled=!writable();input.dataset.key='hostile-'+key;input.autocomplete='off';
    if(key==='name')input.pattern='[A-Za-z0-9_]{1,16}';input.addEventListener('input',()=>hostileDraft[key]=input.value);field.append(input);form.append(field);
  }
  form.append(el('p','hint','A UUID follows name changes. Without one, the entry matches the username until an attributed attack supplies its UUID. Names alone may be reused by another player.'));
  const submit=el('button','primary','Add player');submit.type='submit';submit.disabled=!writable();form.append(submit);form.addEventListener('submit',event=>{event.preventDefault();control({op:'hostile-add',...hostileDraft});});$('content').append(form);
  section('Known hostiles',catalog.players.length+' / 256 entries · removal forgives old reports; a new attributed attack can add the player again');
  if(!catalog.players.length){$('content').append(empty('No known hostiles','Add a username, or let updated workers report a server-attributed attack.'));return;}
  const table=el('table');const head=el('thead'),row=el('tr');for(const title of ['Player','Server','Identity','Evidence','Added',''])row.append(el('th','',title));head.append(row);table.append(head);const body=el('tbody');
  for(const player of catalog.players){const row=el('tr');for(const text of [player.name,player.server,player.uuid||'Username only',player.source==='attack'?'Worker-reported attacker':'Manual',new Date(player.addedAt).toLocaleString()])row.append(el('td','',text));const action=el('td');action.append(button('Remove',()=>control({op:'hostile-remove',id:player.id},'Forgive '+player.name+' on '+player.server+'?'),!writable()));row.append(action);body.append(row);}table.append(body);const wrap=el('div','table-wrap');wrap.append(table);$('content').append(wrap);
}
async function loadIncidents(){
  if(incidentView.loading||demo||!token||!snapshot?.incidentSummary)return;
  const key=[incidentView.before,incidentView.kind,incidentView.search].join('\n'),attempt=generation;
  const query=new URLSearchParams({limit:'100',kind:incidentView.kind,search:incidentView.search});if(incidentView.before)query.set('before',incidentView.before);
  incidentView.loading=true;
  try{const data=await resource('GET','incidents?'+query);if(attempt!==generation||key!==[incidentView.before,incidentView.kind,incidentView.search].join('\n'))return;incidentView.data=data;incidentView.error='';}
  catch(error){if(attempt===generation)incidentView.error=error.message;}
  finally{incidentView.loading=false;if(page==='incidents')render();}
}
function renderIncidents(){
  if(!snapshot.incidentSummary){$('content').append(empty('Host update required','Install 0.14.2 or later for the incident timeline.'));return;}
  const controls=el('form','card'),kindLabel=el('label','','Event type'),kind=el('select');kind.dataset.key='incident-kind';
  for(const [value,title] of [['','All events'],['sighting','Player sightings'],['damage','Damage'],['death','Deaths'],['crystal','Crystals'],['hostile_logout','Hostile logouts'],['reconnect','Safety reconnects']]){const option=el('option','',title);option.value=value;kind.append(option);}kind.value=incidentView.kind;
  kind.addEventListener('change',()=>{incidentView.kind=kind.value;incidentView.before='';incidentView.data=null;loadIncidents();});kindLabel.append(kind);
  const searchLabel=el('label','','Search worker, player or server'),search=el('input');search.maxLength=100;search.value=incidentView.search;search.dataset.key='incident-search';search.addEventListener('input',()=>{incidentView.search=search.value;});searchLabel.append(search);
  const submit=el('button','primary','Search');submit.type='submit';controls.append(kindLabel,searchLabel,submit);controls.addEventListener('submit',e=>{e.preventDefault();incidentView.before='';incidentView.data=null;loadIncidents();});$('content').append(controls);
  const data=incidentView.data,summary=data||snapshot.incidentSummary;
  section('Observed events',number(summary.retained)+' / '+number(summary.capacity)+' retained · latest first');
  $('content').append(el('p','hint','Sightings record loaded player models entering view, with a ten-second absence debounce. Detected lobbies and known workers are excluded. Names, locations and confidence are worker-reported; crystal suspects are not confirmed attackers.'));
  if(incidentView.error)$('content').append(el('p','error',incidentView.error));
  if(summary.dropped||summary.error||summary.unreadableRecords)$('content').append(el('p','error','History may be incomplete: '+number(summary.dropped)+' disk writes dropped; '+number(summary.unreadableRecords)+' unreadable records. '+(summary.error||'')));
  if(data?.cursorExpired)$('content').append(el('p','hint','This page aged out. Select Latest to return to retained history.'));
  for(const event of data?.items||[]){
    const evidence=event.evidence||{},actor=evidence.player||evidence.attacker||{},card=el('article','card');
    card.append(el('h3','',event.kind.replaceAll('_',' ')+' · '+event.workerName+(actor.name?' · '+actor.name:'')),el('p','hint',new Date(event.receivedAt).toLocaleString()+' · '+(event.crew||'No crew')));
    const location=evidence.player||evidence;card.append(el('p','', [evidence.classification||evidence.attribution||evidence.phase||'',evidence.scope||evidence.server||'',Number.isFinite(location.x)?[location.x,location.y,location.z].map(v=>Math.floor(v)).join(', '):''].filter(Boolean).join(' · ')));
    const details=jsonDetails('Evidence',evidence);details.dataset.key='incident-'+event.id;card.append(details);$('content').append(card);
  }
  if(data&&!data.items?.length)$('content').append(empty('No matching events','Try another filter, or wait for worker observations.'));
  $('content').append(button('Latest',()=>{incidentView.before='';incidentView.data=null;loadIncidents();}),button('Older',()=>{incidentView.before=data.nextCursor;incidentView.data=null;loadIncidents();},!data?.hasMore));
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
    if(worker.implementation)name.append(el('small','',worker.implementation.id+' '+worker.implementation.version));
    const position = state.native || worker; const coords = ['x', 'y', 'z'].every(k => Number.isFinite(position[k])) ? [position.x, position.y, position.z].map(Math.floor).join(', ') : '—';
    const status = el('td'); status.append(badge(state.status, tone(state.status))); const actions = el('td');
    actions.append(button('Manage', () => inspect('worker', worker.id), false, '', 'worker-' + worker.id));
    const resources=el('td');resources.append(resourceLine(state.native?.resourceCounts),predictionLine(state.native?.roadPrediction,worker.crew,worker.id));
    row.append(name, el('td', '', worker.crew), status, resources, el('td', '', coords), el('td', '', worker.positionFresh ? Math.round(worker.observationAgeMs || 0) + ' ms ago' : 'Stale'), actions); body.append(row);
  }
  table.append(body); panel.append(table); return panel;
}
function jobActions(task) {
  const actions = el('div', 'actions operator-live');
  const current=()=>[...snapshot.tasks,...snapshot.history].find(t=>t.id===task.id)||task;
  const pause=button('Pause job',()=>control({op:'pause',id:task.id}),!writable(),' ',task.id+'-pause');
  const resume=button('Resume job',()=>control({op:'resume',id:task.id}),!writable(),' ',task.id+'-resume');
  const cancel=button('Cancel job',()=>control({op:'cancel',id:task.id},'Cancel this whole job? Offline workers reconcile on reconnect; recovery records are retained.'),!writable(),'danger',task.id+'-cancel');
  const remove=button('Delete history',()=>control({op:'delete',id:task.id},'Delete this finished record?'),true,'danger',task.id+'-delete');
  const joining=button('Open joining',()=>control({op:'public-join',id:task.id,enabled:!current().publicJoin}),!writable());
  actions.append(pause,resume,cancel,joining,remove);
  actions.updateStatus=()=>{const t=current(),dead=terminal(t.status);pause.disabled=!writable()||dead||!!t.paused;resume.disabled=!writable()||dead;cancel.disabled=!writable()||dead;remove.hidden=!dead;remove.disabled=!writable()||!dead||t.cleanupPending;joining.hidden=dead||!t.nativeDefinition;joining.disabled=!writable();joining.textContent=t.publicJoin?'Close joining':'Open joining';};actions.updateStatus();
  actions.append(button('Inspect', () => inspect('job', task.id), false, '', task.id + '-inspect')); return actions;
}
function jobCard(task) {
  const card = el('article', 'card job-card'); const header = el('div', 'card-header'); header.append(el('h3', '', task.name), badge(task.status, tone(task.status))); card.append(header);
  const meta = el('div', 'job-meta'),distance=el('div');card.append(meta,distance);
  const detail=el('p','job-detail'),guidance=el('p','hint operator-live');
  guidance.updateStatus=()=>{const t=[...snapshot.tasks,...snapshot.history].find(v=>v.id===task.id)||task;detail.textContent=t.detail||'Awaiting worker checkpoint';guidance.textContent=t.crewTransfer?'Crew batch barrier: '+t.crewTransfer.phase+' · '+Object.values(t.crewTransfer.members||{}).filter(m=>m.ready).length+'/'+Object.keys(t.crewTransfer.members||{}).length+' ready · '+t.crewTransfer.delivered+' verified deliveries. Travel waits for every worker.':t.operatorGuidance||'Host-owned job. Pause/Resume/Cancel affect all assigned workers; Detach affects one.';header.lastChild.textContent=t.status;header.lastChild.className='pill '+tone(t.status);const fields=[t.crew,t.workflowName||'Workflow','Priority '+t.priority,Object.keys(t.runs||{}).length+' workers',t.publicJoin?'Public joining':'Invite only'];if(t.kitJob)fields.push(kitJobLabel(t.kitJob));meta.replaceChildren(...fields.map(v=>el('span','',v)));distance.replaceChildren();if(t.nativeDefinition?.length)progress(distance,t.highwayProgress||0,t.nativeDefinition.length);};guidance.updateStatus();
  card.append(detail,guidance,jobActions(task));
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
  try {
    const {op,id,worker} = request;
    if (['pause','resume','cancel'].includes(op)) await resource('POST', 'jobs/' + id + '/' + op, {});
    else if (op === 'delete') await resource('DELETE', 'jobs/' + id);
    else if (op === 'detach') await resource('POST', 'jobs/' + id + '/workers/' + worker + '/' + (request.detached ? 'detach' : 'rejoin'), {});
    else if (op === 'priority' && !worker) await resource('PATCH', 'jobs/' + id, { priority: request.priority });
    else if (op === 'crew-create') await resource('POST', 'crews', { id, name: request.name });
    else if (op === 'crew-rename') {const crew=await crewResource(request.crew);await resource('PATCH','crews/'+crew.id,{name:request.name},undefined,crew.revision);}
    else if (op === 'crew-delete') {const crew=await crewResource(request.crew);await resource('DELETE','crews/'+crew.id);}
    else if (op === 'crew-move') {const crew=await crewResource(request.crew);await resource('PUT','crews/'+crew.id+'/workers/'+worker,{});}
    else await api('control', request);
    notice('Host accepted ' + op + '. Worker cleanup and delivery may continue independently.', true);
  }
  catch (error) { notice(error.message + ' Refresh status before retrying; the command may already have reached the host.'); }
  finally { busy = false; await refresh(); }
}
function inspect(kind, id) { inspecting = { kind, id }; showInspection(); if (!$('inspect-dialog').open) $('inspect-dialog').showModal(); loadInspection(); }
async function loadInspection() {
  const selected=inspecting;if(!selected||selected.loading||demo)return;
  selected.loading=true;showInspection();
  try {
    const record=selected.kind==='crew'?await crewResource(selected.id):await resource('GET',selected.kind+'s/'+selected.id);
    if(inspecting!==selected)return;
    selected.record=record;selected.loadedAt=Date.now();selected.error='';
  } catch(error) {if(inspecting===selected)selected.error=error.message;}
  finally {if(inspecting===selected){selected.loading=false;showInspection();}}
}
function jsonDetails(title, value) { const node = el('details'); node.append(el('summary', '', title), el('pre', '', JSON.stringify(value, null, 2))); return node; }
function configurationInspector(task) {
  const panel=el('details');panel.append(el('summary','','Captured configuration & live requests'));
  panel.dataset.preserve='configuration:'+task.id;
  const body=el('div','management-section');panel.append(body);
  let loaded=false;
  const load=async()=>{
    const refresh=button('Refresh configuration snapshot',load,demo);
    try {
      const data=await resource('GET','jobs/'+task.id+'/configuration');
      // Preserve the selected source on manual refresh; ordinary polling never rebuilds this panel.
      const previous=body.querySelector('select')?.value;
      body.replaceChildren(el('p','hint',data.explanation),refresh,el('p','hint','Snapshot '+new Date().toLocaleTimeString()));
      const select=el('select');select.setAttribute('aria-label','Configuration source');
      const choices=[];
      for(const [name,modules] of Object.entries(data.profiles||{}))choices.push({name:'Captured profile · '+name,key:'profile:'+name,modules});
      for(const [id,update] of Object.entries(data.updates||{}))choices.push({name:'Latest live request · '+(snapshot.workers.find(w=>w.id===id)?.name||id),key:'worker:'+id,modules:update.modules,update});
      choices.forEach(choice=>{const option=el('option','',choice.name);option.value=choice.key;select.append(option);});
      if(choices.some(choice=>choice.key===previous))select.value=previous;
      const list=el('div');body.append(select,list);
      const show=()=>{
        list.replaceChildren();const choice=choices.find(c=>c.key===select.value);if(!choice){list.append(el('p','hint','No captured profiles.'));return;}
        if(choice.update){const u=choice.update;list.append(badge(u.status,u.status==='Rejected'?'bad':u.status==='Pending'?'attention':''),el('p','hint','Requested revision '+u.requestedRevision+' · acknowledged '+u.acknowledgedRevision),el('p','',u.error));}
        const entries=Object.entries(choice.modules||{}).sort(([a],[b])=>a.localeCompare(b));
        if(!entries.length)list.append(el('p','hint','No module overrides in this source.'));
        for(const [name,value] of entries){const module=el('details');const executor=['highway-builder','printer-helper','schematic-selector'].includes(name);module.append(el('summary','',name+' · '+(executor?'Job-controlled':value.active?'On':'Off')),el('pre','',value.settings),el('p','hint','Serialized setting overrides; omitted settings retain worker values.'));list.append(module);}
      };
      select.addEventListener('change',show);show();
      body.append(configurationComparison(task,data));
      body.append(jsonDetails('Captured highway duties and supply capabilities',data.highways));loaded=true;
    }catch(error){body.replaceChildren(el('p','',error.message),refresh);loaded=false;}
  };
  panel.addEventListener('toggle',()=>{if(panel.open&&!loaded){loaded=true;if(demo){body.append(el('p','hint','Connect to a host to inspect captured configuration.'));}else load();}});
  return panel;
}
function configurationComparison(task,data) {
  const sources={...data.profiles};const latest=Object.assign({},...Object.values(data.updates||{}).map(update=>update.modules));if(Object.keys(latest).length)sources['Latest live request / worker']=latest;
  const panel=el('details');panel.append(el('summary','','Compare personal / host / actual settings'));
  const body=el('div','management-section'),worker=el('select'),profile=el('select'),module=el('select'),result=el('div');panel.append(body);
  const field=(title,input)=>{const label=el('label','',title);label.append(input);body.append(label);};
  for(const id of Object.keys(data.updates||{})){const o=el('option','',snapshot.workers.find(w=>w.id===id)?.name||id);o.value=id;worker.append(o);}
  for(const name of Object.keys(sources)){const o=el('option','',name);o.value=name;profile.append(o);}
  field('Worker',worker);field('Host source',profile);field('Module',module);
  let revision=0,pending=false;
  const changed=()=>{revision++;pending=false;result.replaceChildren(el('p','hint','Selection changed. Request a new snapshot.'));};
  const modules=()=>{module.replaceChildren();for(const name of Object.keys(sources[profile.value]||{}).sort()){const o=el('option','',name);o.value=name;module.append(o);}changed();};
  profile.addEventListener('change',modules);worker.addEventListener('change',changed);module.addEventListener('change',changed);modules();
  const renderSample=sample=>{
    result.replaceChildren(el('p','',sample.status));
    if(!sample.report)return;
    result.append(el('p','hint','Snapshot received '+new Date(sample.receivedAt).toLocaleString()+'. Refresh after edits or reconnects.'),el('p','hint',sample.report.note));
    const wrapper=el('div');wrapper.style.overflowX='auto';const table=el('table'),head=el('thead'),titles=el('tr'),rows=el('tbody');
    for(const title of ['Setting','Personal','Selected host overlay','Actual now']){const th=el('th','',title);th.scope='col';titles.append(th);}head.append(titles);
    for(const row of sample.report.rows){const tr=el('tr');for(const key of ['setting','personal','requested','current']){const td=el('td','',row[key]);td.style.whiteSpace='pre-wrap';td.style.overflowWrap='anywhere';tr.append(td);}rows.append(tr);}table.append(head,rows);wrapper.append(table);result.append(wrapper);
  };
  const request=button('Request fresh comparison',async()=>{
    if(pending||!writable()||!module.value||!worker.value)return;
    const version=++revision,session=generation,selected={id:task.id,worker:worker.value,profile:profile.value,module:module.value};pending=true;
    const relevant=()=>version===revision&&session===generation&&panel.isConnected&&panel.open;
    try{
      let sample=await api('control',{op:'configuration-read',...selected});
      if(!relevant())return;renderSample(sample);
      const deadline=Date.now()+12000;
      while(sample.status==='Pending'&&Date.now()<deadline){
        await new Promise(resolve=>setTimeout(resolve,500));if(!relevant())return;
        sample=await api('control',{op:'configuration-read-result',id:selected.id,worker:selected.worker});
        if(!relevant())return;
        if(sample.profile!==selected.profile||sample.module!==selected.module){result.replaceChildren(el('p','','Readback was replaced by another operator. Request again.'));return;}
        renderSample(sample);
      }
    }catch(error){if(relevant())result.replaceChildren(el('p','',error.message));}
    finally{if(version===revision)pending=false;}
  },demo);
  body.append(el('p','hint','Read-only, on-demand worker snapshot. Requires a 0.8.2+ worker. Personal means its pre-job checkpoint (or current settings when no job owns them). Captured overlays may differ from later live edits.'),request,result,
    el('p','hint','To keep a received profile, open Workers → job → Inspect configuration on that client and choose Save captured profile as personal copy. The web host cannot overwrite personal profiles.'));
  panel.addEventListener('toggle',()=>{if(!panel.open){revision++;pending=false;}});
  return panel;
}
function managedButton(text, action, cls='') { return button(text,action,!writable(),'managed-control '+cls); }
function guidedSettings(task, initialWorker) {
  const panel=el('details','guided-settings');panel.append(el('summary','','Edit live job settings'));
  panel.dataset.preserve='guided:'+task.id+':'+(initialWorker||'');
  const body=el('div','management-section');panel.append(body);
  let loaded=false;
  panel.addEventListener('toggle',async()=>{
    if(!panel.open||loaded)return;loaded=true;
    if(demo){body.append(el('p','hint','Connect to a host to edit settings.'));return;}
    try {
      const catalog=await resource('GET','configuration-controls');
      if(!panel.isConnected)return;
      body.append(el('p','hint','This job only. Values are proposed edits, not readings from workers. Future-job defaults and other settings are unchanged.'));
      const form=el('form'),target=el('select'),setting=el('select'),activation=el('select'),value=el('input');value.type='number';
      const field=(title,input)=>{const label=el('label','',title);label.append(input);form.append(label);};
      let option=el('option','','All unfinished workers in this job');option.value='';target.append(option);
      for(const id of Object.keys(task.runs||{})){option=el('option','',snapshot.workers.find(w=>w.id===id)?.name||id);option.value=id;target.append(option);}
      target.value=initialWorker||'';
      for(const c of catalog.controls){option=el('option','',c.label);option.value=c.id;setting.append(option);}
      for(const state of ['On','Off']){option=el('option','',state);option.value=state;activation.append(option);}
      field('Apply to',target);field('Control',setting);field('Proposed module activation',activation);field('Proposed value (not read from worker)',value);
      const help=el('p','hint'),preview=el('p','','Choose values, then preview the change.'),receipts=el('div');receipts.setAttribute('aria-live','polite');
      preview.style.whiteSpace='pre-line';receipts.style.whiteSpace='pre-line';
      const previewButton=el('button','','Preview change');previewButton.type='submit';
      const apply=button('Apply previewed change',async()=>{
        if(!draft||!writable())return;
        const request=draft;draft=null;busy=true;panel.updateStatus();
        try {await resource('PATCH','jobs/'+task.id+'/configuration',request);preview.textContent='Request saved. Await the worker acknowledgement below.';}
        catch(error){preview.textContent='Request not confirmed: '+error.message+' Check acknowledgements before retrying.';}
        finally {busy=false;await refresh();panel.updateStatus();}
      },true);
      let draft=null,revision=0,previewing=false;
      const changed=()=>{revision++;draft=null;preview.textContent='Draft changed. Preview again before applying.';panel.updateStatus?.();};
      const updateValue=()=>{const c=catalog.controls.find(c=>c.id===setting.value);value.parentElement.hidden=!c.setting;value.required=!!c.setting;value.min=c.min;value.max=c.max;value.step=c.step||1;value.value=c.example;help.textContent=c.help;changed();};
      target.addEventListener('change',changed);activation.addEventListener('change',changed);value.addEventListener('input',changed);setting.addEventListener('change',updateValue);
      panel.updateStatus=()=>{
        const current=[...snapshot.tasks,...snapshot.history].find(t=>t.id===task.id);
        const allowed=writable()&&current&&!terminal(current.status)&&!current.cancelled;
        previewButton.disabled=!allowed||previewing;apply.disabled=!allowed||!draft;
        const lines=Object.entries(current?.runs||{}).filter(([id])=>!target.value||id===target.value).map(([id,run])=>{
          const requested=run.configuration?.revision||0,ack=run.configRevision||0;
          const status=!requested?'No live request':ack<requested?(terminal(run.status)||current.cancelled?'Ended without acknowledgement':'Pending'):run.configError?'Rejected':'Accepted';
          return (snapshot.workers.find(w=>w.id===id)?.name||id)+': '+status+' · requested '+requested+' / acknowledged '+ack+(requested&&ack>=requested&&run.configError?' · '+run.configError:'');
        }).join('\n');
        if(receipts.textContent!==lines)receipts.textContent=lines;
      };
      form.addEventListener('submit',async e=>{
        e.preventDefault();if(!writable()||previewing)return;
        const c=catalog.controls.find(c=>c.id===setting.value),version=revision,worker=target.value;
        draft=null;previewing=true;panel.updateStatus();
        try {
          const request={op:'preview-configuration',control:c.id,active:activation.value==='On'};
          if(c.setting)request.value=Number(value.value);
          const result=await api('control',request);
          if(version!==revision||!panel.isConnected)return;
          draft={control:c.id,active:activation.value==='On',...(c.setting?{value:Number(value.value)}:{}),...(worker?{workerId:worker}:{})};
          preview.textContent='Target: '+target.selectedOptions[0].textContent+'\n'+result.description+'\n'+result.scope;
        }catch(error){if(version===revision)preview.textContent='Cannot preview: '+error.message;}
        finally{previewing=false;panel.updateStatus();}
      });
      form.append(help,previewButton,preview,apply);body.append(form,el('h4','','Latest acknowledgements'),receipts);updateValue();
    } catch(error) {body.replaceChildren(el('p','',error.message+' Close and reopen to retry.'));loaded=false;}
  });
  return panel;
}
function assignFromManagement(crew, worker) {
  openJob({crew,worker});
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
  panel.dataset.preserve='chat:'+crew+':'+(worker||'');
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
    for(const id of Object.keys(task.runs||{}))if(!worker||id===worker)panel.append(participationControls(task,id));
    panel.append(configurationInspector(task));
    if(!terminal(task.status))panel.append(guidedSettings(task,worker));
    const details=el('details');details.append(el('summary','','Advanced: raw module configuration · '+task.name));
    details.dataset.preserve='raw:'+task.id+':'+(worker||'');
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
    panel.dataset.preserve='membership:'+crew+':'+worker;
    const select=el('select');select.setAttribute('aria-label','Destination crew');for(const id of snapshot.crews){const option=el('option','',snapshot.crewLabels?.[id]||id);option.value=id;select.append(option);}select.value=crew;
    panel.classList.add('operator-live');panel.updateStatus=()=>{const selected=select.value,options=snapshot.crews.map(id=>{const o=el('option','',snapshot.crewLabels?.[id]||id);o.value=id;return o;});if(JSON.stringify([...select.options].map(o=>[o.value,o.textContent]))!==JSON.stringify(options.map(o=>[o.value,o.textContent]))){select.replaceChildren(...options);select.value=snapshot.crews.includes(selected)?selected:crew;}select.disabled=!writable();};
    actions.append(select,managedButton('Move worker',()=>control({op:'crew-move',crew:select.value,worker},'Move this worker? Its jobs must be finished/cancelled and cleanup acknowledged first.')));
  } else {
    actions.append(managedButton('Rename crew',()=>{const name=prompt('Crew name',snapshot.crewLabels?.[crew]||crew);if(name)control({op:'crew-rename',crew,name});}),
      managedButton('Delete crew',()=>control({op:'crew-delete',crew},'Delete this crew? The host refuses if workers, jobs, or recovery would be orphaned.'),'danger'));
  }
  panel.append(actions);content.append(panel);
}
function priorityControls(task, workerId) {
  const row = el('div', 'priority-row operator-live'); const input = el('input'); input.type = 'number'; input.min = -1000; input.max = 1000; input.step = 1;
  row.dataset.preserve='priority:'+task.id+':'+(workerId||'');
  input.value = workerId ? task.overrides?.[workerId] ?? task.priority : task.priority; input.setAttribute('aria-label', workerId ? 'Worker job priority' : 'Job priority');
  row.append(el('span', 'hint', workerId ? 'Worker priority' : 'Job priority'), input, button('Set priority', () => {
    if (!input.checkValidity() || input.value === '') { input.reportValidity(); return; }
    control({ op: 'priority', id: task.id, priority: Number(input.value), ...(workerId ? { worker: workerId } : {}) });
  }, !writable()));row.updateStatus=()=>{const t=[...snapshot.tasks,...snapshot.history].find(t=>t.id===task.id);input.disabled=!writable()||!t||terminal(t.status);row.querySelector('button').disabled=input.disabled;};return row;
}
function participationControls(task,worker) {
  const row=el('div','inspection-row operator-live'),status=el('p'),toggle=button('Detach worker',()=>{
    const t=[...snapshot.tasks,...snapshot.history].find(t=>t.id===task.id),r=t?.runs?.[worker];if(!r)return;
    const away=r.operatorDetached||r.stallPaused;control({op:'detach',id:task.id,worker,detached:!away},away?null:'Detach only this worker from this job? Its checkpoint and any cleanup remain; other eligible workers keep working.');
  },!writable());
  row.append(status,toggle);
  row.updateStatus=()=>{const t=[...snapshot.tasks,...snapshot.history].find(t=>t.id===task.id)||task,r=t.runs?.[worker];if(!r)return;status.textContent=(snapshot.workers.find(w=>w.id===worker)?.name||worker)+' · '+(r.stallPaused?'Stall pause · ':r.operatorDetached?'Detached by operator · ':'')+r.status+' · '+(r.detail||'Awaiting worker report');toggle.textContent=r.operatorDetached||r.stallPaused?'Rejoin worker':'Detach worker';toggle.disabled=!writable()||terminal(t.status)||terminal(r.status);};row.updateStatus();return row;
}
function actionProgressText(p) {
  return p ? `${p.state} · ${p.phase} · awaiting ${p.expectedNextEvent} · ${Math.floor(p.progressAgeMs/1000)}s without progress · ${p.recoveryAttempts}/2 navigation retries${p.target?' · target '+p.target:''}${p.closestDistance!=null?' · closest approach '+p.closestDistance.toFixed(1)+' blocks':''}` : 'Action progress unavailable on this worker';
}
function inspectionShape(){if(!inspecting||!snapshot)return '';const {kind,id}=inspecting;const jobs=[...snapshot.tasks,...snapshot.history].filter(t=>kind==='job'?t.id===id:kind==='crew'?t.crew===id:t.runs?.[id]);return JSON.stringify([kind,id,jobs.map(t=>[t.id,terminal(t.status),Object.keys(t.runs||{})]),snapshot.workers.map(w=>[w.id,w.crew])]);}
function showInspection() {
  if (!inspecting || !snapshot) return;
  const { kind, id } = inspecting; const content = $('inspect-content'),key=kind+':'+id,same=key===inspectionKey;
  const kept=new Map(same?[...content.querySelectorAll('[data-preserve]')].map(n=>[n.dataset.preserve,n]):[]);
  const expanded=new Set(same?[...content.querySelectorAll('details[open]')].map(n=>n.querySelector('summary')?.textContent):[]);
  const scroll=same?$('inspect-dialog').scrollTop:0,focus=same?document.activeElement:null;
  inspectionKey=key;inspecting.shape=inspectionShape();content.replaceChildren();
  content.append(button('Refresh inspection ↻', async () => { await refresh(); await loadInspection(); if(demo)showInspection(); }), el('p', 'hint', 'Live diagnostics updated ' + new Date(refreshedAt || Date.now()).toLocaleTimeString() + '; resource inspection ' + (demo?'unavailable in demo':inspecting.loading?'loading':inspecting.error?'failed: '+inspecting.error:inspecting.loadedAt?'read at '+new Date(inspecting.loadedAt).toLocaleTimeString():'pending') + '. Uncertain resources are not cleared by opening this view.'));
  if (kind === 'stash') {
    const db=inspecting.record; $('inspect-title').textContent=db?.name || 'Stash'; if(!db)return;
    content.append(el('p','hint',db.scope+' · observations can be stale; scan again before withdrawing'));
    for(const container of Object.values(db.containers || {})) {
      const row=el('article','inspection-row');row.append(el('h3','',container.x+', '+container.y+', '+container.z+' · '+container.block),badge(container.status,container.status==='observed'?'good':'bad'),el('p','hint',container.reason || 'Server contents observed '+new Date(container.observedAt).toLocaleString()),jsonDetails('Items and shulker classification',container));content.append(row);
    }
    return;
  }
  if (kind === 'job') {
    const task = [...snapshot.tasks, ...snapshot.history].find(t => t.id === id); $('inspect-title').textContent = task?.name || 'Job no longer present'; if (!task) return;
    content.append(el('p', 'muted', task.server + ' · ' + task.dimension), jobActions(task)); if (!terminal(task.status)) content.append(priorityControls(task));
    content.append(configurationInspector(task));
    const receiptView=el('section','operator-live inspection-row');receiptView.updateStatus=()=>{const t=[...snapshot.tasks,...snapshot.history].find(t=>t.id===id)||task;const receipts=Object.values(t.controlReceipts||{}),latest=receipts.at(-1);const expanded=receiptView.querySelector('details')?.open;receiptView.replaceChildren(el('h3','','Command receipt'),el('p','hint','Host acceptance and send-queue admission are not worker success or game-server verification.'));if(latest){receiptView.append(el('p','job-detail',latest.command+' · '+latest.state.replaceAll('_',' ')));const evidence=jsonDetails('Per-worker acknowledgement and outcome',latest);evidence.open=!!expanded;receiptView.append(evidence);}else receiptView.append(el('p','hint','No correlated command receipt yet.'));};receiptView.updateStatus();content.append(receiptView);
    if(!terminal(task.status))content.append(guidedSettings(task));
    for (const [workerId, run] of Object.entries(task.runs || {})) {
      const worker = snapshot.workers.find(w => w.id === workerId); const row = el('article', 'inspection-row'); const header = el('div', 'card-header');
      header.append(el('h3', '', worker?.name || workerId), badge(run.status, tone(run.status)));const detail=el('p'),cleanup=el('p','hint'),progress=el('p','hint');row.append(header,detail,cleanup,progress);
      row.classList.add('operator-live');row.updateStatus=()=>{const r=[...snapshot.tasks,...snapshot.history].find(t=>t.id===id)?.runs?.[workerId]||run;header.lastChild.textContent=r.status;header.lastChild.className='pill '+tone(r.status);detail.textContent=r.detail||'No reported detail';cleanup.textContent=r.requestedStatus?'Pending cleanup target: '+r.requestedStatus:'';progress.textContent=actionProgressText(r.actionProgress);};row.updateStatus();
      if (run.stashScan) row.append(stashPanel(run));
      if (!terminal(task.status)) row.append(priorityControls(task, workerId)); content.append(row);
      if(!terminal(task.status))content.append(participationControls(task,workerId));
    }
    if (snapshot.highways[task.crew]?.execution) content.append(jsonDetails('Crew verification, supply reservations and exchange state', snapshot.highways[task.crew]));
    if(inspecting.record)content.append(jsonDetails('Job resource', inspecting.record));
  } else if (kind === 'crew') {
    const native = snapshot.highways[id] || {}; $('inspect-title').textContent = snapshot.crewLabels?.[id]||id;
    const live=el('div','operator-live');live.updateStatus=()=>{const n=snapshot.highways[id]||{};live.replaceChildren(resourceLine(n.resourceCounts,true),predictionLine(n.roadPrediction,id));};live.updateStatus();content.append(live);managementControls(content,id);chatPanel(content,id);crewControls(content,id);
    const roster=el('div','operator-live');roster.updateStatus=()=>roster.replaceChildren(workerTable(snapshot.workers.filter(w=>w.crew===id)));roster.updateStatus();content.append(roster);
    for(const task of snapshot.tasks.filter(t=>t.crew===id))for(const run of Object.values(task.runs || {}))if(run.stashScan)content.append(stashPanel(run));
    if (native.execution) content.append(button('End native highway', () => control({ op: 'end-highway', crew: id }, 'End this crew’s native highway and cancel its owning job? Outstanding supplies remain recorded.'), !writable(), 'danger'));
    if(inspecting.record)content.append(jsonDetails('Crew resource', inspecting.record));
    content.append(jsonDetails('Supply ownership, verification and diagnostics', native), eventList((native.events || []).slice(-20).reverse()));
  } else {
    const worker = snapshot.workers.find(w => w.id === id); $('inspect-title').textContent = worker?.name || 'Worker no longer connected'; if (!worker) return;
    const combat=el('div','operator-live');combat.updateStatus=()=>{const w=snapshot.workers.find(w=>w.id===id);const open=combat.querySelector('details')?.open;combat.replaceChildren();if(w?.combat&&(w.combat.lastDamage||w.combat.lastDeath||w.combat.lastCrystalExplosion||w.combat.lastHostileLogout)){const details=jsonDetails('Attack, death and hostile logout evidence · suspects are not confirmed attackers',w.combat);details.open=!!open;combat.append(details);}};combat.updateStatus();content.append(combat);
    const state = workerState(worker),live=el('div','operator-live');live.updateStatus=()=>{const w=snapshot.workers.find(w=>w.id===id);if(!w){live.replaceChildren(el('p','hint','Worker no longer present'));return;}const s=workerState(w);live.replaceChildren(el('p','job-detail',s.status+' · '+s.detail),el('p','hint',w.implementation?w.implementation.id+' '+w.implementation.version+' · '+w.id:w.id),el('p','hint',w.worldReadiness?'World: '+w.worldReadiness.state+' · '+w.worldReadiness.reason:'World readiness: not reported by this worker'),resourceLine(s.native?.resourceCounts),predictionLine(s.native?.roadPrediction,w.crew,w.id));};live.updateStatus();content.append(live);
    if(state.run?.stashScan)content.append(stashPanel(state.run));
    managementControls(content,worker.crew,worker.id);chatPanel(content,worker.crew,worker.id);crewControls(content,worker.crew,worker.id);
    if(inspecting.record)content.append(jsonDetails('Worker resource', inspecting.record));
    if(state.native)content.append(jsonDetails('Native crew diagnostics', state.native));
  }
  for(const node of content.querySelectorAll('[data-preserve]')){const old=kept.get(node.dataset.preserve);if(old)node.replaceWith(old);}
  for(const node of content.querySelectorAll('details'))if(expanded.has(node.querySelector('summary')?.textContent))node.open=true;
  if(focus?.isConnected)focus.focus({preventScroll:true});$('inspect-dialog').scrollTop=scroll;
  updateChat();document.querySelectorAll('.operator-live').forEach(n=>n.updateStatus?.());
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
const shulkerColors={white:'#dfe3e8',orange:'#ed9b51',magenta:'#c677c4',light_blue:'#72b9df',yellow:'#e2c15e',lime:'#9cc960',pink:'#e99cb9',gray:'#777e88',light_gray:'#b7bdc4',cyan:'#61b5b4',purple:'#aa81cb',blue:'#728bd0',brown:'#aa8467',green:'#80aa71',red:'#d2807a',black:'#666d7a',uncolored:'#9d8aaf'};
function shulkerLabel(row,item) {
  const match=/^minecraft:(?:(\w+)_)?shulker_box$/.exec(item||'');if(!match)return item;
  const color=match[1]||'uncolored';row.classList.add('shulker-row');row.style.setProperty('--shulker-color',shulkerColors[color]||shulkerColors.uncolored);row.title=item;
  return color.replaceAll('_',' ').replace(/^./,letter=>letter.toUpperCase())+' shulker box';
}
function kitSignature(type) { return JSON.stringify([type.item,type.name||'',Object.entries(type.items||{}).sort(([a],[b])=>a.localeCompare(b))]); }
function kitBoxType(box,types,aliases={}) {
  if(box.kitTypeId){const id=aliases[box.kitTypeId]||box.kitTypeId;return types[id]?id:'';}
  const candidates=Object.entries(types).filter(([,type])=>box.item===type.item&&(box.name||'')===(type.name||'')&&(Object.keys(box.items||{}).some(item=>Number(box.items[item])>0&&Number(type.items?.[item])>0)||!Object.keys(box.items||{}).length&&!Object.keys(type.items||{}).length));
  return candidates.length===1?candidates[0][0]:'';
}
function kitCatalog(stashes) {
  const catalog=new Map();
  for(const stash of stashes)for(const [id,type] of Object.entries(stash.kitTypes||{})){
    const key=kitSignature(type),counts=(stashes.length>1?stash.deduplicatedKitCounts??stash.kitCounts:stash.kitCounts)?.[id]||{};
    if(!catalog.has(key))catalog.set(key,{key,type,sources:[],complete:0,incomplete:0,estimatedComplete:0,estimatedIncomplete:0,estimated:false});
    const kit=catalog.get(key);kit.sources.push({stash,id});kit.complete+=Number(counts.complete||0);kit.incomplete+=Number(counts.incomplete||0);
    const estimate=(!stash.duplicateContainers||stashes.length===1)?stash.transferKitCounts?.[id]:null;
    kit.estimatedComplete+=Number((estimate||counts).complete||0);kit.estimatedIncomplete+=Number((estimate||counts).incomplete||0);kit.estimated||=!!estimate;
  }
  return [...catalog.values()].sort((a,b)=>(b.estimatedComplete+b.estimatedIncomplete)-(a.estimatedComplete+a.estimatedIncomplete)||(a.type.name||a.type.item).localeCompare(b.type.name||b.type.item));
}
function kitStockText(kit) {
  const observed=number(kit.complete)+' complete · '+number(kit.incomplete)+' incomplete observed';
  return kit.estimated?number(kit.estimatedComplete)+' complete · '+number(kit.estimatedIncomplete)+' incomplete estimated; '+observed:observed;
}
function kitReserveBalance(stash,id) {
  const floor=Number(stash.kitReserves?.[id]||0),stock=Number((stash.transferKitCounts?.[id]||stash.kitCounts?.[id]||{}).complete||0);
  return {floor,stock,surplus:Math.max(0,stock-floor),shortfall:Math.max(0,floor-stock)};
}
function kitReserveText(stash,id) {
  const r=kitReserveBalance(stash,id);
  return r.floor?'Reserve '+number(r.floor)+' · '+(r.shortfall?number(r.shortfall)+' below floor':number(r.surplus)+' above floor'):'No reserve set';
}
function kitReserveEditor(stash,id) {
  const panel=el('section','management-section'),label=el('label','','Reserve floor (complete kits)'),input=el('input'),message=el('p','hint',kitReserveText(stash,id));
  input.type='number';input.min='0';input.max='1000000';input.step='1';input.value=String(kitReserveBalance(stash,id).floor);input.disabled=!writable();label.append(input);
  const save=button('Save reserve',async()=>{
    const floor=Number(input.value);if(input.value===''||!Number.isInteger(floor)||floor<0||floor>1000000){message.textContent='Enter 0–1,000,000 whole kits.';return;}
    save.disabled=true;
    try{const saved=await api('control',{op:'stash-kit-reserve',crew:stash.crew,scope:stash.scope,name:stash.name,expected:stash.revision,kitTypeId:id,floor});Object.assign(stash,saved);message.textContent='Saved · '+kitReserveText(stash,id);await refresh();}
    catch(e){message.textContent=e.message+' Close and reopen this kit after refreshing if the catalog changed.';}
    finally{save.disabled=!writable();}
  },!writable());
  panel.append(label,save,message,el('p','hint','Advisory floor for this kit in this stash only. 0 clears it. Incomplete kits do not satisfy the reserve. Does not block deliveries, transfers or whisper requests; surplus is an estimate, not guaranteed stock.'));return panel;
}
function kitLocations(db,source) {
  const rows=[];
  for(const record of Object.values(db.containers||{})){
    const observed=record.status==='observed'&&!record.inferred;
    const boxes=(record.shulkers||[]).filter(b=>kitBoxType(b,db.kitTypes||source.stash.kitTypes,db.kitAliases)===source.id);
    const mapped=db.columns?.[record.x+','+record.z]?.kitTypeId===source.id;
    if(!boxes.length&&!mapped&&!(record.withdrawnKitTypes||[]).includes(source.id))continue;
    rows.push({x:record.x,y:record.y,z:record.z,count:observed?boxes.reduce((n,b)=>n+Number(b.quantity||0),0):null,status:observed?'Observed':record.inferred?'Inferred':'Changed · rescan needed',observedAt:record.observedAt});
  }
  return rows.sort((a,b)=>a.y-b.y||a.x-b.x||a.z-b.z);
}
function deliveryWorkers(stash,workers) {
  return workers.filter(w=>w.connected&&w.reconciled&&!w.publicProtocol&&w.scope===stash.scope&&(stash.crew==='Local'||w.crew===stash.crew));
}
function closeKit() {
  if(busy&&$('kit-dialog').open)return;
  if(pendingKitDelivery&&!confirm('This delivery may already be accepted. Close without retrying? Check Jobs before creating replacement work.'))return;
  kitDelivery=pendingKitDelivery=null;$('kit-dialog').close();
}
$('kit-close').addEventListener('click',closeKit);
$('kit-dialog').addEventListener('cancel',event=>{event.preventDefault();closeKit();});
function openKit(kit,preferred=null,delivery=false) {
  if(pendingKitDelivery){$('kit-dialog').showModal();return;}
  kitDelivery={kit};$('kit-title').textContent=kit.type.name||kit.type.item.replace('minecraft:','').replaceAll('_',' ');
  $('kit-details').replaceChildren();$('delivery-error').textContent='';$('kit-delivery-fields').disabled=false;$('delivery-submit').textContent='Deliver kits';
  const contents=el('section','kit-contents');contents.append(el('h3','','Contents per full kit'),el('p','hint','Fullest observed exemplar. Name and color group related kits; unrelated contents have separate type IDs. Individual boxes may be incomplete.'),el('p','hint','Type '+kit.sources[0].id));
  const table=el('table','worker-table');for(const [item,count] of Object.entries(kit.type.items||{}).sort((a,b)=>b[1]-a[1])){const row=el('tr');row.append(el('td','',item.replace('minecraft:','').replaceAll('_',' ')),el('td','',number(count)));table.append(row);}contents.append(table);$('kit-details').append(contents,el('h3','','Where to find it'));
  $('delivery-source').replaceChildren();
  kit.sources.forEach((source,index)=>{
    const stash=source.stash,[server,dimension]=stash.scope.split('\n'),bounds=stash.bounds||{},card=el('section','kit-location');
    card.append(el('h3','',stash.name),el('p','hint',server+' · '+dimension+' · '+(snapshot.crewLabels?.[stash.crew]||stash.crew)),el('p','hint','Bounds '+[bounds.minX,bounds.minY,bounds.minZ].join(', ')+' → '+[bounds.maxX,bounds.maxY,bounds.maxZ].join(', ')+' · /home '+(bounds.homeName||'not set')));
    const counts=stash.kitCounts?.[source.id]||{},estimate=stash.transferKitCounts?.[source.id];card.append(el('p','',kitStockText({...counts,estimated:!!estimate,estimatedComplete:estimate?.complete,estimatedIncomplete:estimate?.incomplete})+' · catalog '+new Date(stash.updatedAt).toLocaleString()));
    card.append(kitReserveEditor(stash,source.id));
    const locations=el('div');locations.append(el('p','hint','Loading observed chest coordinates…'));card.append(locations);$('kit-details').append(card);
    const option=el('option','',stash.name+' · '+dimension+' · '+number(counts.complete)+' complete');option.value=String(index);$('delivery-source').append(option);
    if(!demo)(async()=>{try{const db=await resource('GET','stashes/'+await stashResourceId(stash));if(kitDelivery?.kit!==kit)return;const rows=kitLocations(db,source),details=el('details');details.append(el('summary','',rows.length+' known chest locations · upper mapped stock may be unknown'));const cells=el('table','worker-table');for(const r of rows){const row=el('tr');row.append(el('td','',[r.x,r.y,r.z].join(', ')),el('td','',r.count===null?'Unknown':number(r.count)+' boxes'),el('td','',r.status));cells.append(row);}details.append(cells);locations.replaceChildren(rows.length?details:el('p','hint','No current matching chest observations. Stash bounds remain available above.'));}catch(e){locations.replaceChildren(el('p','error','Chest locations unavailable: '+e.message));}})();
    else locations.replaceChildren(el('p','hint','Chest details unavailable in read-only demo.'));
  });
  const selected=kit.sources.findIndex(s=>s.stash===preferred);$('delivery-source').value=String(selected<0?0:selected);
  $('delivery-count').value='1';$('delivery-incomplete').checked=false;$('delivery-return-home').checked=true;
  $('delivery-usernames').replaceChildren(...[...new Set(snapshot.workers.map(w=>w.name).filter(n=>/^[A-Za-z0-9_]{1,16}$/.test(n)))].map(name=>{const option=el('option');option.value=name;return option;}));
  updateDeliveryWorkers();$('kit-dialog').showModal();$('kit-dialog').scrollTop=0;if(delivery)$('delivery-count').focus();
}
function updateDeliveryWorkers() {
  const source=kitDelivery?.kit.sources[Number($('delivery-source').value)],previous=$('delivery-worker').value,workers=source?deliveryWorkers(source.stash,snapshot.workers):[];
  const values=workers.map(w=>w.id).join(',');if($('delivery-worker').dataset.roster===values){updateKitDeliveryState();return;}
  $('delivery-worker').dataset.roster=values;$('delivery-worker').replaceChildren();
  for(const worker of workers){const option=el('option','',worker.name+(worker.current?' · currently working':' · ready'));option.value=worker.id;$('delivery-worker').append(option);}
  if([...$('delivery-worker').options].some(o=>o.value===previous))$('delivery-worker').value=previous;
  updateKitDeliveryState();
}
function updateKitDeliveryState() {
  const source=kitDelivery?.kit.sources[Number($('delivery-source').value)];
  const worker=source&&deliveryWorkers(source.stash,snapshot.workers).find(w=>w.id===$('delivery-worker').value);
  $('delivery-submit').disabled=!writable()||!worker;
  $('delivery-hint').textContent=worker?'Collect, /tpa, approach a safe fixed spot and aim the drop; optionally /home back to this stash. The recipient must accept. Requires client/host 0.14.30+. Priority 100; busy workers yield after safe cleanup. Gear is not stored before departure; observed stock and recipient pickup are not guaranteed.':'No connected, reconciled Monocle worker in this stash’s crew and world. Connect one to deliver.';
}
$('delivery-source').addEventListener('change',updateDeliveryWorkers);
function kitDeliveryAccepted() {
  pendingKitDelivery=null;kitDelivery=null;$('kit-dialog').close();notice('Kit delivery queued. Track it in Jobs; the recipient must accept the TPA.',true);
}
$('kit-delivery-form').addEventListener('submit',async event=>{
  event.preventDefault();if(!writable())return;$('delivery-error').textContent='';busy=true;$('kit-delivery-fields').disabled=true;$('delivery-submit').disabled=true;
  try{
    if(!pendingKitDelivery){
      const source=kitDelivery?.kit.sources[Number($('delivery-source').value)],count=Number($('delivery-count').value),name=$('delivery-recipient').value.trim();
      if(!source||!Number.isInteger(count)||count<1||count>36||!/^[A-Za-z0-9_]{1,16}$/.test(name))throw Error('Choose a source, 1–36 shulkers and an exact Minecraft username.');
      const selected=deliveryWorkers(source.stash,snapshot.workers).find(w=>w.id===$('delivery-worker').value);if(!selected)throw Error('Choose a connected worker in the source stash’s crew and world.');
      const identity=await resource('GET','players/'+encodeURIComponent(name)+'?workerId='+encodeURIComponent(selected.id));
      const worker=await resource('GET','workers/'+selected.id);
      if(!worker.connected||!worker.reconciled||worker.publicProtocol||worker.crew!==selected.crew||worker.scope!==source.stash.scope)throw Error('Worker state changed. Refresh and choose the delivery worker again.');
      if(identity.id===worker.id)throw Error('Choose a recipient other than the worker running this delivery.');
      const [server,dimension]=source.stash.scope.split('\n');
      const args={...source.stash.bounds,name:source.stash.name,kitTypeId:source.id,count,destination:'Player',recipient:identity.id,recipientName:identity.name,includeIncomplete:$('delivery-incomplete').checked,transferAll:false,returnHome:$('delivery-return-home').checked};
      const packet=await api('control',{op:'workflow-prepare',id:'task-kit-delivery',scope:source.stash.scope,args});
      pendingKitDelivery={key:crypto.randomUUID(),body:{id:crypto.randomUUID(),name:'Deliver '+count+' '+(kitDelivery.kit.type.name||'kits')+' to '+identity.name,crewId:worker.crewId,workerIds:[worker.id],scope:{server,dimension},priority:100,args,package:packet}};
    }
    await resource('POST','jobs',pendingKitDelivery.body,pendingKitDelivery.key);kitDeliveryAccepted();
  }catch(error){
    if(!error.uncertain)pendingKitDelivery=null;
    $('delivery-error').textContent=error.message+(pendingKitDelivery?' Outcome uncertain: retry reuses this exact delivery and command ID. Do not submit a replacement.':'');
  }finally{busy=false;$('kit-delivery-fields').disabled=!!pendingKitDelivery;$('delivery-submit').textContent=pendingKitDelivery?'Retry same delivery':'Deliver kits';await refresh();}
});
function stashKey(stash) { return JSON.stringify([stash.crew,stash.scope,stash.name]); }
function kitColor(type) {
  let hash=0;for(const char of kitSignature(type))hash=(Math.imul(hash,31)+char.charCodeAt(0))|0;
  return 'hsl('+((hash>>>0)%360)+' 55% 65%)';
}
function stashFullness(stash) {
  const panel=el('div','stash-fullness'),s=stash.storage;
  if(!s?.slots){panel.append(el('strong','','Fullness unknown'),el('small','hint','Scan storage containers to measure capacity.'));return panel;}
  const percent=Math.min(100,100*s.occupiedSlots/s.slots),partial=s.unknownContainers>0,estimate=s.estimatedContainers>0;
  const label=(partial?'At least ':estimate?'~':'')+percent.toFixed(1)+'% full';
  panel.append(el('strong','',label));const meter=el('progress');meter.max=100;meter.value=percent;meter.setAttribute('aria-label',label+' · recorded storage');panel.append(meter);
  panel.append(el('small','hint',number(s.occupiedSlots)+' / '+number(s.slots)+' recorded slots · '+number(s.containers)+' storage containers'));
  if(partial)panel.append(el('small','hint',number(s.unknownContainers)+' containers have unknown occupancy'));
  if(estimate)panel.append(el('small','hint','Legacy capacity estimate; assumes double chests'));
  panel.title='Recorded chest/barrel slot occupancy; hoppers and nested shulker contents are excluded. Partial stacks occupy one slot. Unobserved containers are not included. Rescan on the updated client for exact slot counts.';return panel;
}
function renderStashes() {
  const stashes=snapshot.stashes||[];
  if(!stashes.length){$('content').append(empty('No stash observations yet','Select a cuboid in Stash Manager, then export its definition and assign a scan.'));return;}
  if(!selectedStash){
    const search=el('input');search.type='search';search.placeholder='Find a stash';search.value=stashFilter;search.setAttribute('aria-label','Find a stash');search.dataset.key='stash-search';search.addEventListener('input',()=>{stashFilter=search.value;render();});$('content').append(search);
    const list=el('div','stash-directory');
    for(const stash of stashes.filter(s=>[s.name,s.scope].join(' ').toLowerCase().includes(stashFilter.toLowerCase())).sort((a,b)=>a.name.localeCompare(b.name))){
      const link=el('a','stash-directory-link');link.href='#stashes/'+encodeURIComponent(stashKey(stash));link.dataset.key='stash-'+stashKey(stash);
      const name=el('div');name.append(el('h2','',stash.name),el('p','hint',stash.scope.replace('\n',' · ')),el('p','hint',number(stash.observed)+' observed · '+Object.keys(stash.kitTypes||{}).length+' kit types · /home '+(stash.bounds?.homeName||'not set')));
      link.append(name,stashFullness(stash));list.append(link);
    }
    $('content').append(list.childElementCount?list:empty('No matching stashes','Try another name or world.'));return;
  }
  const back=el('a','stash-back','← All stashes');back.href='#stashes';$('content').append(back);
  const current=stashes.find(s=>stashKey(s)===selectedStash);
  if(!current){$('content').append(empty('Stash unavailable','This stash is no longer in the host catalog. Return to the directory to choose another.'));return;}
  $('breadcrumb').textContent='Stashes / '+current.name;$('page-title').textContent=current.name;
  const detail=el('article','card stash-detail');detail.dataset.stash=selectedStash;
  const header=el('div','stash-heading'),name=el('div');name.append(el('p','eyebrow','STORAGE DESK'),el('h2','','Storage overview'),el('p','hint',current.scope.replace('\n',' · ')+' · /home '+(current.bounds?.homeName||'not set')),badge(current.observed?'Observed catalog':'Not scanned',current.observed?'healthy':''));header.append(name,stashFullness(current));detail.append(header);
  const bounds=current.bounds||{};detail.append(el('p','stash-bounds',[bounds.minX,bounds.minY,bounds.minZ].join(', ')+' → '+[bounds.maxX,bounds.maxY,bounds.maxZ].join(', ')),el('p','hint',number(current.observed)+' observed · '+number(current.inferred)+' inferred · '+number(current.unscanned)+' unscanned · '+new Date(current.updatedAt).toLocaleString()));
  const emptyMode=!!bounds.assumeEmpty;
  detail.append(el('p','hint',emptyMode?'Assume-empty destination · '+number(current.assumedEmptyContainers||0)+' discovered unopened chests · '+number(current.assumedEmptySlots||0)+' assumed slots (not verified free space). Deposits verify each chest first.':'Only observed compatible columns are eligible for new deposits.'));
  detail.append(button(emptyMode?'Disable empty-storage assumption':'Use as empty storage (no contents scan)',async()=>{
    if(!writable()||!confirm(emptyMode?'Stop using unopened chests as deposit candidates? Existing observations and reservations will remain.':'Treat newly discovered unopened chests as empty destination candidates? Known contents remain unchanged. Enable client live storage updates and load the stash chunks to map chest locations. Every deposit still verifies contents first.'))return;
    try{await api('control',{op:'stash-update',crew:current.crew,scope:current.scope,name:current.name,expected:current.revision,bounds:{assumeEmpty:!emptyMode}});await refresh();}catch(e){notice(e.message);}
  },!writable()));
  const actions=el('div','actions');actions.append(button('Scan with workers',async()=>{try{await openJob(pendingSubmission?{}:{crew:current.crew,stash:current,id:await stashResourceId(current)});}catch(e){notice(e.message);}},!writable()),button('Inspect containers',async()=>{try{inspect('stash',await stashResourceId(current));}catch(e){notice(e.message);}}));detail.append(actions);
  detail.append(stashSortPreview(current));
  const tabs=el('nav','storage-tabs stash-subtabs');tabs.setAttribute('aria-label','Selected stash views');
  for(const [id,label] of [['columns','Columns'],['kits','Kits'],['resources','Resources']]){const tab=button(label,()=>{stashTab=id;render();},false,stashTab===id?'active':'','stash-tab-'+id);tab.setAttribute('aria-current',stashTab===id?'page':'false');tabs.append(tab);}detail.append(tabs);
  const scans=snapshot.tasks.filter(t=>Object.values(t.runs||{}).some(r=>r.stashScan?.name===current.name)&&t.crew===current.crew&&t.server+'\n'+t.dimension===current.scope);
  for(const task of scans){const scan=el('div','stash-scan-status');scan.append(badge(task.status,tone(task.status)),el('span','',Object.values(task.runs).find(r=>r.stashScan?.name===current.name)?.detail||'Scan in progress'),button('View scan',()=>inspect('job',task.id)));detail.append(scan);}
  if(stashTab==='columns')detail.append(stashColumnMap(current));
  if(stashTab==='kits')detail.append(stashKitTable(current));
  if(stashTab==='resources'){
    detail.append(el('h3','','Observed resources'),el('p','hint','Includes shulker contents. These are this stash’s own counts, not its contribution to deduplicated global totals.'));
    const table=el('table','worker-table');for(const [item,count] of Object.entries(current.items||{}).sort((a,b)=>b[1]-a[1])){const row=el('tr');row.append(el('td','',shulkerLabel(row,item)),el('td','',number(count)));table.append(row);}const panel=el('div','table-panel');panel.append(table.childElementCount?table:empty('No observed stock','A partial scan does not imply an empty stash.'));detail.append(panel);
  }
  $('content').append(detail);
}
function stashSortPreview(stash) {
  const key=stashKey(stash),entry=stashSortReads.get(key)||{buffers:4,carrySlots:34,growthColumns:1},panel=el('section','column-map-panel');stashSortReads.set(key,entry);
  panel.append(el('h3','','Category sorting · preview & write mode'),el('p','hint','Preview the host layout first. This never changes reservations, moves kits or starts a job. Write mode is a separate, explicitly confirmed action. Unknown contents stay in place.'));
  const lease=[...snapshot.tasks,...snapshot.history].find(t=>t.crew===stash.crew&&t.server+'\n'+t.dimension===stash.scope&&t.stashSort?.name===stash.name&&t.stashSort.leased);
  if(lease){const s=lease.stashSort;panel.append(el('p','',lease.name+' · '+lease.status+' · '+number(s.cursor)+' / '+number(s.columns)+' columns settled · '+number(s.moved)+' kits relocated · '+(s.phase||'Between batches')),button('Manage sort job',()=>inspect('job',lease.id)));
    if(s.cargo)panel.append(el('p','hint','Retained batch: '+number(s.cargo.picked-s.cargo.deposited)+' kits. Its journal survives cancellation.'));
    if(s.scanRetries)panel.append(el('p','hint','Verification retry '+number(s.scanRetries)+' / 3; no inventory moves until the column is verified.'));
    if(s.retryableRead)panel.append(button('Retry failed verification',async()=>{try{await api('control',{op:'resume',id:lease.id});await refresh();render();}catch(e){notice(e.message);}},!writable()));
    if(terminal(lease.status))panel.append(button('Release stopped sort after recovering cargo',async()=>{if(!confirm('Have you recovered every carried kit and stopped all inventory actions? Releasing allows other jobs; the old ledger remains for inspection.'))return;try{await api('control',{op:'stash-sort-release',id:lease.id,confirmCargoRecovered:true});await refresh();render();}catch(e){notice(e.message);}},!writable()));
  }
  const controls=el('div','actions');
  for(const [field,label,max] of [['buffers','Empty buffer columns',16],['carrySlots','Maximum carried kit slots',36],['growthColumns','Growth columns between categories',2]]){
    const wrap=el('label','',label+' '),input=el('input');input.type='number';input.min=field==='growthColumns'?'0':'1';input.max=String(max);input.value=String(entry[field]);input.setAttribute('aria-label',label);input.dataset.key='sort-'+field;input.disabled=!!entry.loading;
    input.addEventListener('change',()=>{entry[field]=Number(input.value);render();});wrap.append(input);controls.append(wrap);
  }
  if(entry.plan){
    const selector=el('div');selector.append(el('p','hint','Choose category rules for this preview. Overlapping rules preserve affected columns; disabling a rule here does not edit its definition.'));
    for(const [id,c] of Object.entries(entry.plan.categoryDefinitions).sort((a,b)=>a[1].name.localeCompare(b[1].name))){
      const label=el('label','',c.name+' '),check=el('input');check.type='checkbox';check.checked=(entry.categoryIds||entry.plan.selectedCategoryIds).includes(id);check.disabled=!!entry.loading;check.setAttribute('aria-label','Use category '+c.name);check.dataset.key='sort-category-'+id;
      check.addEventListener('change',()=>{const ids=new Set(entry.categoryIds||entry.plan.selectedCategoryIds);if(check.checked)ids.add(id);else ids.delete(id);entry.categoryIds=[...ids].sort();render();});label.prepend(check);selector.append(label,document.createTextNode(' '));
    }panel.append(selector);
  }
  controls.append(button(entry.loading?'Planning…':entry.plan?'Refresh sorting preview':'Preview category sort',async()=>{
    if(entry.loading||demo)return;
    if(!Number.isInteger(entry.buffers)||entry.buffers<1||entry.buffers>16||!Number.isInteger(entry.carrySlots)||entry.carrySlots<1||entry.carrySlots>36||!Number.isInteger(entry.growthColumns)||entry.growthColumns<0||entry.growthColumns>2){entry.error='Choose 1–16 buffers, 1–36 carried kit slots and 0–2 growth columns.';render();return;}
    entry.loading=true;entry.error='';const session=generation;render();
    try{const id=await stashResourceId(stash),filter=entry.categoryIds?'&categoryIds='+encodeURIComponent(entry.categoryIds.join(',')):'',plan=await resource('GET','stashes/'+id+'/sort-preview?buffers='+entry.buffers+'&carrySlots='+entry.carrySlots+'&growthColumns='+entry.growthColumns+filter);if(session!==generation)return;entry.plan=plan;entry.sortSubmission=null;entry.readAt=Date.now();}
    catch(e){if(session===generation)entry.error=e.message;}
    finally{entry.loading=false;if(session===generation&&page==='stashes'&&selectedStash===key)render();}
  },demo||!!entry.loading));panel.append(controls);
  if(entry.error)panel.append(el('p','error',entry.error));
  if(!entry.plan)return panel;
  const plan=entry.plan,s=plan.summary,names=new Map(plan.categories.map(c=>[c.categoryId,c.name]));
  const changedSettings=s.bufferColumnsRequested!==entry.buffers||plan.carrySlots!==entry.carrySlots||plan.growthColumns!==entry.growthColumns||(entry.categoryIds&&JSON.stringify([...entry.categoryIds].sort())!==JSON.stringify([...plan.selectedCategoryIds].sort()));
  panel.append(el('p','hint','Host snapshot '+new Date(entry.readAt).toLocaleTimeString()+' · '+(changedSettings?'Settings changed—refresh the preview.':plan.stashRevision!==stash.revision?'Catalog changed since this preview—refresh it.':'Snapshot-bound proposal; refresh after category edits.')));
  panel.append(el('p','',number(s.columns)+' columns · '+number(s.emptyColumns)+' empty now → '+number(s.projectedEmptyColumns)+' projected empty (includes '+number(s.bufferColumnsReserved)+' buffers) · '+number(s.pinnedColumns)+' preserved columns'));
  panel.append(el('p','hint',number(s.growthColumnsReserved)+' growth columns reserved for the category immediately to their left · '+number(s.unassignedEmptyColumns)+' unassigned empty columns, plus buffers'+(s.additionalCategoryOwnedEmptyColumns?' · '+number(s.additionalCategoryOwnedEmptyColumns)+' additional category-owned empty columns':'')+'.'));
  panel.append(el('p','hint',number(s.movableKits)+' recorded kits eligible · '+number(s.pinnedKits)+' preserved in place · '+number(s.unmappedKits)+' in unmapped containers. Unknown contents are not counted as zero stock.'));
  const feasible=plan.feasibleForMovableColumns;
  const executors=snapshot.workers.filter(w=>w.connected&&w.reconciled&&w.crew===stash.crew&&w.scope===stash.scope&&w.stashSortSupported),workerLabel=el('label','','Sorting courier '),workerSelect=el('select');workerSelect.setAttribute('aria-label','Sorting courier');
  for(const w of executors){const option=el('option','',w.name);option.value=w.id;workerSelect.append(option);}entry.worker=executors.some(w=>w.id===entry.worker)?entry.worker:executors[0]?.id||'';workerSelect.value=entry.worker;workerSelect.addEventListener('change',()=>{entry.worker=workerSelect.value;render();});workerLabel.append(workerSelect);panel.append(workerLabel);
  panel.append(button(entry.sortSubmission?'Retry same sort submission':'Start experimental write sort',async()=>{
    if(!entry.sortSubmission){if(!confirm('This will relocate kits and rewrite completed-column reservations. Protected columns stay untouched. Empty carried shulkers first. Start this experimental single-worker sort?'))return;entry.sortSubmission={op:'stash-sort-start',id:crypto.randomUUID(),crew:stash.crew,scope:stash.scope,name:stash.name,worker:entry.worker,planId:plan.planId,buffers:entry.buffers,carrySlots:entry.carrySlots,growthColumns:entry.growthColumns,categoryIds:plan.selectedCategoryIds};}
    entry.loading=true;render();try{const job=await api('control',entry.sortSubmission);entry.sortSubmission=null;await refresh();inspect('job',job.id);}catch(e){entry.error=e.message;}finally{entry.loading=false;render();}
  },!writable()||!!entry.loading||!!lease||(!entry.sortSubmission&&(!feasible||changedSettings||plan.stashRevision!==stash.revision||!entry.worker))));
  if(!executors.length)panel.append(el('p','hint','Connect a sorting-capable client (0.15.9+) to enable write mode. No live sort starts from a preview.'));
  panel.append(badge(feasible?'Fits eligible columns':'Insufficient space / buffers',feasible?'healthy':'warning'));
  if(feasible)panel.append(el('p','',number(s.kitsToMove)+' kits to relocate · at least '+number(s.minimumInventoryLoads)+' inventory loads · '+number(s.pairwiseInventoryLoads)+' loads if each source/destination pair is handled separately. Staging and travel are extra.'));
  else panel.append(el('p','error',number(s.capacityShortfall)+' kits lack eligible destination capacity · '+number(s.growthColumnsShortfall)+' growth gaps unavailable. Check buffers too; no executable schedule exists.'));
  for(const warning of plan.warnings)panel.append(el('p','hint',warning));
  if(plan.unclassifiedKitTypeIds.length)panel.append(el('p','hint','Needs unambiguous categories: '+plan.unclassifiedKitTypeIds.map(id=>stash.kitTypes?.[id]?.name||id).join(', ')));
  const table=el('table','worker-table'),head=el('tr');for(const label of ['Category','Kits to arrange','Target columns','Already in place','Kits to move'])head.append(el('th','',label));table.append(head);
  for(const c of plan.categories){const row=el('tr');for(const value of [c.name,number(c.kits),number(c.columns),number(c.keptInPlace),number(c.kitsToMove)])row.append(el('td','',value));table.append(row);}const scroll=el('div','table-panel');scroll.append(table);panel.append(scroll);
  panel.append(button(entry.showColumns?'Hide proposed columns':'Show proposed columns',()=>{entry.showColumns=!entry.showColumns;render();}));
  if(entry.showColumns){
    const layout=el('table','worker-table'),heading=el('tr');for(const label of ['Column','Bottom position','Recorded kits','Proposed category','Planned kits'])heading.append(el('th','',label));layout.append(heading);
    for(const c of plan.columns){const row=el('tr');row.title=c.preserveReasons.join('; ');for(const value of [number(c.index),[c.x,c.y,c.z].join(', '),number(c.currentKits),(names.get(c.target)||({preserve:'Preserve / needs inspection',buffer:'Temporary buffer',empty:'Empty / unassigned'}[c.target]||c.target))+(c.growth?' · growth reserve':''),number(c.plannedKits)])row.append(el('td','',value));layout.append(row);}const map=el('div','table-panel');map.append(layout);panel.append(map);
  }
  return panel;
}
function stashKitTable(stash) {
  const card=el('div','stash-kit-stock');
  const types=Object.entries(stash.kitTypes||{}),canDispatch=writable()&&snapshot.workers.some(worker=>worker.connected&&worker.reconciled&&worker.scope===stash.scope&&(stash.crew==='Local'||worker.crew===stash.crew));
  if(types.length){card.append(el('h3','','Kits · stock'));const kits=el('table','worker-table');
    for(const [id,type] of types){const kit=kitCatalog([stash]).find(kit=>kit.sources.some(s=>s.id===id)),row=el('tr'),name=el('td'),label=shulkerLabel(row,type.item);row.title+=' · '+id;name.append(button(type.name||label,()=>openKit(kit,stash),false,'text-button'),el('small','',label));row.append(name,el('td','',kitStockText(kit)));
      row.append(el('td','',kitReserveText(stash,id)));
      const controls=el('td'),actions=el('div','actions');actions.append(button('Reserve',()=>openKit(kit,stash),!writable()),button('Deliver',()=>openKit(kit,stash,true),!canDispatch),button('Remove incomplete',()=>openJob({crew:stash.crew,kitStash:stash,kitTypeId:id,preset:'task-kit-remove-incomplete'}),!canDispatch));controls.append(actions);row.append(controls);kits.append(row);}
    const kitTable=el('div','table-panel');kitTable.append(kits);card.append(kitTable,el('p','hint','Observed counts come from chest snapshots. Transfer-adjusted estimates track confirmed deposits and withdrawals until a full fresh rescan reconciles them. External changes can make estimates stale; incomplete means below the fullest observed exemplar.'));}
  else card.append(el('p','hint','No kit types observed yet. Scan a stash column to classify its shulkers.'));
  return card;
}
// Display projection only. Reservation writes are independently checked against recorded anchors by the host.
function storageColumns(db,layout='auto') {
  const records=Object.values(db.containers||{}).filter(r=>r.status!=='removed'&&(!r.block||/(?:^|:)(?:chest|trapped_chest)$/.test(r.block)));
  if(!records.length)return {columns:[],layout:'vertical',bottom:null};
  const bottom=Math.min(...records.map(r=>r.y)),anchors=new Set(records.filter(r=>r.y===bottom).map(r=>r.x+','+r.z));
  const offsets={vertical:[0,0],'+Z':[0,1],'-Z':[0,-1],'+X':[1,0],'-X':[-1,0],'zigzag+Z':[0,1],'zigzag-Z':[0,-1],'zigzag+X':[1,0],'zigzag-X':[-1,0]};
  if(layout==='auto'){
    layout='vertical';let best=-1;
    for(const [name,[dx,dz]] of Object.entries(offsets)){const score=records.filter(r=>{const step=name.startsWith('zigzag')?(r.y-bottom)%2:r.y-bottom;return anchors.has((r.x-dx*step)+','+(r.z-dz*step));}).length;if(score>best){best=score;layout=name;}}
  }
  const [dx,dz]=offsets[layout]||offsets.vertical,columns=new Map(),types=db.kitTypes||{};
  for(const r of records){
    const step=layout.startsWith('zigzag')?(r.y-bottom)%2:r.y-bottom,x=r.x-dx*step,z=r.z-dz*step,key=x+','+z;
    if(!columns.has(key))columns.set(key,{key,x,z,bottom,records:[],ids:new Set(),boxes:0,empty:true,unknown:false,inferred:false,mixed:false});
    const column=columns.get(key);column.records.push(r);
    const measured=r.status==='observed'&&!r.inferred;
    column.unknown ||= r.status!=='observed';column.inferred ||= !!r.inferred;
    column.empty &&= measured&&Object.keys(r.items||{}).length===0&&(r.shulkers||[]).length===0;
    const covered={};
    for(const box of r.shulkers||[]){const id=kitBoxType(box,types,db.kitAliases);if(id)column.ids.add(id);else column.mixed=true;
      if(measured)column.boxes+=Number(box.quantity||0);
      covered[box.item]=(covered[box.item]||0)+Number(box.quantity||0);
      if(!box.legacy)for(const [item,count] of Object.entries(box.items||{}))covered[item]=(covered[item]||0)+count*Number(box.quantity||0);
    }
    if(measured&&Object.entries(r.items||{}).some(([item,count])=>count!==(covered[item]||0)))column.mixed=true;
  }
  for(const c of columns.values()){
    c.reservation=db.columnReservations?.[c.key];c.mixed ||= c.ids.size>1;
    c.state=c.reservation?.blocked?'conflict':c.mixed?'mixed':c.ids.size?'kit':c.unknown?'unknown':c.inferred?'inferred':c.empty?'empty':'mixed';
    c.label=c.ids.size?[...c.ids].map(id=>types[id]?.name||types[id]?.item||id).join(' + '):c.state==='empty'?'Observed empty':c.state==='unknown'?'Unknown contents':c.state==='inferred'?'Inferred contents':'Loose / mixed resources';
    c.description=[c.label,'Column X '+c.x+' · Z '+c.z,number(c.records.length)+' recorded chests · Y '+Math.min(...c.records.map(r=>r.y))+'–'+Math.max(...c.records.map(r=>r.y)),number(c.boxes)+' directly observed shulkers',c.unknown?'Has unscanned or changed chests':'',c.inferred?'Includes inferred observations':'',c.reservation?'Reserved: '+(db.categoryDefinitions?.[c.reservation.categoryId]?.name||types[c.reservation.kitTypeId]?.name||c.reservation.categoryId||c.reservation.kitTypeId)+(c.reservation.blocked?' · conflict':''):'',c.empty?'Only recorded chests are empty; unobserved upper stock is unknown':''].filter(Boolean).join('\n');
    const fill=db.columnFill?.[c.key];if(fill)c.description+='\nTracked empty-storage fill: '+number(fill.kits)+' / '+number(fill.capacity)+' mapped chest slots'+(fill.uncertain?' · external observation; current occupancy needs verification':' · confirmed net kit transfers since empty baseline');
  }
  return {columns:[...columns.values()].sort((a,b)=>a.z-b.z||a.x-b.x),layout,bottom};
}
function stashColumnMap(stash) {
  const key=stashKey(stash),panel=el('section','column-map-panel'),entry=stashReads.get(key)||{};
  panel.append(el('h3','','Column map'),el('p','hint','Click a column to inspect or reserve it. Shift-click another column to select the inclusive range in map order. Reservations guide transfers; workers still verify actual contents.'));
  if(!demo&&!entry.loading&&(!entry.db||entry.revision!==stash.revision)&&Date.now()-(entry.attempt||0)>5000){
    entry.loading=true;entry.attempt=Date.now();stashReads.set(key,entry);const session=generation;
    (async()=>{try{entry.id ||= await stashResourceId(stash);const db=await resource('GET','stashes/'+entry.id),catalog=await api('control',{op:'kit-categories-get',crew:stash.crew,scope:stash.scope});if(session!==generation)return;db.categoryDefinitions=catalog.categories;entry.db=db;entry.revision=db.revision;entry.error='';entry.readAt=Date.now();}catch(e){if(session===generation)entry.error=e.message;}finally{entry.loading=false;if(session===generation&&page==='stashes'&&selectedStash===key)render();}})();
  }
  if(entry.error)panel.append(el('p','error','Map refresh failed: '+entry.error+' · showing the last reading when available.'));
  if(!entry.db){panel.append(empty(demo?'Map unavailable in demo':'Loading chest layout…','The map reads the existing catalog; it does not start another scan.'));return panel;}
  const db=entry.db,toolbar=el('div','column-toolbar'),selector=el('select');selector.setAttribute('aria-label','Column layout');selector.dataset.key='column-layout';
  for(const [value,label] of [['auto','Auto-detect layout'],['vertical','Vertical columns'],['+Z','Stepped toward +Z'],['-Z','Stepped toward −Z'],['+X','Stepped toward +X'],['-X','Stepped toward −X']]){const option=el('option','',label);option.value=value;selector.append(option);}selector.value=stashLayouts.get(key)||'auto';selector.disabled=!!entry.pendingReservation;selector.addEventListener('change',()=>{stashLayouts.set(key,selector.value);selectedColumn='';selectedColumns.clear();render();});
  const scale=el('select');scale.setAttribute('aria-label','Column scale');scale.dataset.key='column-scale';for(const [value,label] of [['fit','Fit columns'],['comfortable','Larger columns']]){const option=el('option','',label);option.value=value;scale.append(option);}scale.value=columnScale;scale.addEventListener('change',()=>{columnScale=scale.value;render();});
  const model=storageColumns(db,selector.value),columns=model.columns;toolbar.append(selector,scale,el('span','hint',number(columns.length)+' observed column positions · '+model.layout+' layout'+(model.bottom===null?'':' · projection at Y '+model.bottom)+' · read '+new Date(entry.readAt).toLocaleTimeString()+(entry.loading?' · refreshing…':'')));panel.append(toolbar);
  const legend=el('div','column-legend');for(const [state,label] of [['kit','Kit stock'],['empty','Observed empty'],['unknown','Unknown / changed'],['mixed','Mixed / loose'],['inferred','Inferred'],['conflict','Reservation conflict']]){const labelNode=el('span'),swatch=el('i','column-swatch column-'+state);labelNode.append(swatch,document.createTextNode(label));legend.append(labelNode);}panel.append(legend);
  if(!columns.length){panel.append(empty('No chest columns recorded yet','Hoppers and standalone shulker boxes are not storage columns. Unseen space is not marked empty.'));return panel;}
  const viewport=el('div','column-viewport');viewport.dataset.scroll='map-'+key;viewport.setAttribute('aria-label','Observed storage column positions');
  const xs=[...new Set(columns.map(c=>c.x))].sort((a,b)=>a-b),index=new Map(xs.map((x,i)=>[x,i+1]));let lastZ;
  for(const c of columns){if(c.z!==lastZ){lastZ=c.z;const row=el('div','column-map-row'+(columnScale==='fit'?' column-fit':''));row.style.gridTemplateColumns='100px repeat('+xs.length+', '+(columnScale==='fit'?'minmax(6px, 1fr)':'24px')+')';if(columnScale==='fit')row.style.minWidth=(100+xs.length*8)+'px';row.append(el('span','column-axis','Z '+c.z));viewport.append(row);}
    const cell=button('',event=>{
      const anchor=columns.findIndex(column=>column.key===selectedColumn),end=columns.indexOf(c);
      if(event.shiftKey&&anchor>=0)selectedColumns=new Set(columns.slice(Math.min(anchor,end),Math.max(anchor,end)+1).map(column=>column.key));
      else {selectedColumn=c.key;selectedColumns=new Set([c.key]);entry.reservationKit=c.reservation?.categoryId?'category:'+c.reservation.categoryId:c.reservation?.kitTypeId||(c.ids.size===1?[...c.ids][0]:'');}
      render();
    },!!entry.pendingReservation,'column-tile column-'+c.state+(selectedColumns.has(c.key)?' selected':''),'column-'+c.key);cell.style.gridColumn=String(index.get(c.x)+1);cell.dataset.column=c.key;cell.dataset.state=c.state;cell.title=c.description;cell.setAttribute('aria-label',c.description);cell.setAttribute('aria-describedby','column-tooltip');cell.setAttribute('aria-pressed',String(selectedColumns.has(c.key)));
    if(c.ids.size||c.reservation){const colors=c.ids.size?[...c.ids].map(id=>kitColor(db.kitTypes[id])):db.kitTypes[c.reservation.kitTypeId]?[kitColor(db.kitTypes[c.reservation.kitTypeId])]:[];if(colors.length)cell.style.background=colors.length===1?colors[0]:'linear-gradient(135deg,'+colors.map((color,i)=>color+' '+(i*100/colors.length)+'% '+((i+1)*100/colors.length)+'%').join(',')+')';}
    if(c.unknown||c.inferred)cell.classList.add('column-uncertain');cell.append(el('span','',c.reservation?.blocked?'!':c.state==='unknown'?'?':c.state==='empty'?'○':c.state==='mixed'?'≋':c.reservation?'◇':''));viewport.lastChild.append(cell);
  }
  panel.append(viewport,el('p','hint','X increases left → right: '+xs[0]+' → '+xs.at(-1)+'. Coordinate gaps are compressed; absent columns are not assumed empty. Auto layout is a display heuristic; override it above if needed.'));
  const chosen=columns.find(c=>c.key===selectedColumn),details=el('div','column-detail');
  const selection=columns.filter(c=>selectedColumns.has(c.key));
  if(selection.length){
    const controls=el('div','column-reservation-controls'),label=el('label','','Reserve '+selection.length+' column'+(selection.length===1?'':'s')+' for kit type or category'),kits=el('select');kits.setAttribute('aria-label','Column reservation kit type');kits.dataset.key='column-reservation-kit';
    const types=new Map();for(const source of snapshot.stashes)if(source.scope.split('\n')[0].toLowerCase()===stash.scope.split('\n')[0].toLowerCase()&&(source.crew===stash.crew||source.crew==='Local'))for(const [id,type] of Object.entries(source.kitTypes||{}))if(!types.has(id))types.set(id,type);
    const prompt=el('option','','Choose a kit type');prompt.value='';kits.append(prompt);
    for(const c of Object.values(db.categoryDefinitions||{}).filter(c=>c.usage!=='Kitbot')){const option=el('option','','Category · '+c.name+' ('+c.slug+')');option.value='category:'+c.id;kits.append(option);}
    for(const [id,type] of [...types].sort((a,b)=>(a[1].name||a[1].item).localeCompare(b[1].name||b[1].item))){const option=el('option','',(type.name||type.item)+' · '+type.item.replace('minecraft:','')+' · '+id.slice(0,8));option.value=id;kits.append(option);}
    kits.value=entry.reservationKit||'';kits.disabled=!!entry.pendingReservation;kits.addEventListener('change',()=>{entry.reservationKit=kits.value;render();});label.append(kits);controls.append(label);
    const apply=async clear=>{
      busy=true;render();
      try{
        if(!entry.pendingReservation){
          const axis=model.layout.startsWith('zigzag')?'Vertical':model.layout.endsWith('Z')?'X':model.layout.endsWith('X')?'Z':'Vertical';
          const category=!clear&&entry.reservationKit?.startsWith('category:');
          const body={[category?'categoryId':'kitTypeId']:clear?'':category?entry.reservationKit.slice(9):entry.reservationKit,columns:selection.map(c=>({x:c.x,y:c.bottom,z:c.z,axis}))};
          if(!clear&&!entry.reservationKit)throw Error('Choose a kit type or category.');
          entry.id ||= await stashResourceId(stash);entry.pendingReservation={body,key:crypto.randomUUID(),revision:db.revision};
        }
        const pending=entry.pendingReservation;await resource('PUT','stashes/'+entry.id+'/column-reservations',pending.body,pending.key,pending.revision);
        entry.pendingReservation=null;entry.db=null;entry.attempt=0;entry.reservationError='';notice('Column reservations saved. No items were moved.',true);
      }catch(error){entry.reservationError=error.message;if(!error.uncertain)entry.pendingReservation=null;}
      finally{busy=false;await refresh();render();}
    };
    controls.append(button(entry.pendingReservation?'Retry same reservation':'Reserve selected columns',()=>apply(false),!writable()||(!entry.pendingReservation&&!entry.reservationKit),'','reserve-columns'),button('Clear selected reservations',()=>apply(true),!writable()||!!entry.pendingReservation,'','clear-column-reservations'));
    details.append(controls,el('p','hint','Manual reservations take priority. Category columns may mix only matching kits; workers verify contents before every deposit. A busy column cannot be changed. Clear a reservation to return it to automatic allocation.'));
  }
  if(entry.reservationError)details.append(el('p','error',entry.reservationError+(entry.pendingReservation?' Retry sends the exact same command; do not create a replacement.':'')));
  if(chosen){details.append(el('h3','',chosen.label),el('p','column-description',chosen.description));
    for(const id of chosen.ids){const type=db.kitTypes[id],kit=kitCatalog([stash]).find(k=>k.sources.some(s=>s.id===id));if(kit)details.append(button('View '+(type.name||'kit')+' & deliver',()=>openKit(kit,stash),false,'','column-kit-'+id));}
  }else details.append(el('p','hint','Select a column to see its stock, scan uncertainty, reservations and delivery shortcuts.'));
  panel.append(details);return panel;
}
function kitJobLabel(kit){
  return kit.routes?'Round Robin · '+kit.routes.length+' routes · up to '+kit.count+'/turn · '+kit.routes.map(route=>route.name+' → '+route.targetStashName).join(' / '):(kit.enderTransport?'All kits · up to '+kit.count+'/inventory + ender buffer':kit.transferAll?'All kits · up to '+kit.count+'/trip':kit.count+' kit boxes')+' · '+kit.name+' → '+kit.destination;
}
function kitUsage(data) {
  const jobs=[...data.tasks,...data.history].filter(job=>job.kitJob),done=jobs.filter(job=>job.status==='Complete');
  const delivered=done.filter(job=>['task-kit-delivery','task-stash-transfer','task-stash-transfer-team'].includes(job.kitJob.workflow)).reduce((sum,job)=>sum+Number(job.kitJob.delivered??(job.kitJob.transferAll||job.kitJob.routes?0:job.kitJob.count??0)),0);
  const removed=done.filter(job=>job.kitJob.workflow==='task-kit-remove-incomplete').reduce((sum,job)=>sum+Number(job.kitJob.delivered??(job.kitJob.transferAll?0:job.kitJob.count??0)),0);
  const card=el('article','card');card.append(el('p','',number(jobs.length)+' kit jobs · '+number(done.length)+' completed · '+number(jobs.filter(job=>job.status==='Failed').length)+' failed'),el('p','hint',number(delivered)+' boxes delivered/stored · '+number(removed)+' incomplete boxes removed (completed jobs only)'));
  return card;
}
function overallResources(stashes) {
  const unique=new Map();for(const stash of stashes){const key=JSON.stringify([stash.scope,stash.name,stash.bounds]);if(!unique.has(key)||(unique.get(key).updatedAt||0)<(stash.updatedAt||0))unique.set(key,stash);}
  const totals={};for(const stash of unique.values())for(const [item,count] of Object.entries(stash.deduplicatedItems??stash.items??{}))totals[item]=(totals[item]||0)+count;
  const card=el('article','job-card');const table=el('table','worker-table');for(const [item,count] of Object.entries(totals).sort((a,b)=>b[1]-a[1])){const row=el('tr');row.append(el('td','',shulkerLabel(row,item)),el('td','',number(count)));table.append(row);}
  card.append(table.childElementCount?table:el('p','hint','No observed resources yet. Export a stash definition, then assign a scan.'));return card;
}

function renderServers(){
  const catalog=snapshot.minecraftServers||{servers:[],revision:''};
  if(!serverDraft)serverDraft={revision:catalog.revision,servers:catalog.servers.map(s=>({...s,aliases:[...s.aliases]}))};
  const form=el('form','card');form.append(el('p','hint','Use the existing stash/job address as the primary. Aliases assert these addresses share the same Minecraft world. Updated native workers receive this policy automatically. Finish jobs and cleanup before saving; historical catalogs are not merged.'));
  const rows=el('div','management-section');
  for(const server of serverDraft.servers){
    const row=el('div','management-section');
    for(const [key,title] of [['name','Server name'],['address','Primary Minecraft address'],['aliases','Alias addresses (one per line)']]){
      const input=el(key==='aliases'?'textarea':'input');input.value=key==='aliases'?server.aliases.join('\n'):server[key];input.disabled=!writable();
      input.addEventListener('input',()=>{server[key]=key==='aliases'?input.value.split(/\s+/).filter(Boolean):input.value;});const label=el('label','',title);label.append(input);row.append(label);
    }
    row.append(button('Remove server',()=>{serverDraft.servers.splice(serverDraft.servers.indexOf(server),1);render();},!writable()));rows.append(row);
  }
  form.append(rows,button('Add Minecraft server',()=>{serverDraft.servers.push({name:'',address:'',aliases:[]});render();},!writable()),button('Reload saved servers',()=>{serverDraft=null;render();},!writable()));
  const save=el('button','primary','Save Minecraft servers');save.type='submit';save.disabled=!writable();form.append(save);
  form.addEventListener('submit',async event=>{event.preventDefault();if(!writable())return;try{await api('control',{op:'server-aliases-save',expected:serverDraft.revision,servers:serverDraft.servers});serverDraft=null;await refresh();notice('Minecraft server aliases saved.',true);}catch(error){notice(error.message);}});
  $('content').append(form);
}

async function kitBotSettings() {
  const dialog=el('dialog'),body=el('div','management-section');dialog.append(body);document.body.append(dialog);dialog.addEventListener('close',()=>dialog.remove());dialog.showModal();
  const field=(title,input)=>{const label=el('label','',title);label.append(input);body.append(label);return input;},message=el('p','hint');
  try {
    body.append(el('h2','','Kit categories & whisper requests'),el('p','hint','Explicit incoming whispers only: kit <category> <1–36>. Only complete recorded kits at or above the configured stock minimum can be requested. Whitelist usernames and UUIDs are operator-supplied; server whisper text is not cryptographic authentication.'));
    const settings=await api('control',{op:'kitbot-get'}),source=field('Source stash',el('select')),config=settings.config||{};
    for(const s of snapshot.stashes||[]){const option=el('option','',s.name+' · '+s.crew+' · '+s.scope.replace('\n',' / '));option.value=stashKey(s);source.append(option);}
    source.value=stashKey((snapshot.stashes||[]).find(s=>s.name===config.source&&s.scope===config.scope&&s.crew===config.crew)||(snapshot.stashes||[]).find(s=>s.name==='main')||(snapshot.stashes||[])[0]||{});
    const additional=field('Additional source stashes (same crew and world)',el('select'));additional.multiple=true;additional.size=4;
    const minimum=field('Minimum complete kits per variant',el('input'));minimum.type='number';minimum.min=250;minimum.max=1000000;minimum.step=1;minimum.value=config.minimumStock??270;
    const stockMinimum=()=>{const n=Number(minimum.value);if(!Number.isInteger(n)||n<250||n>1000000)throw Error('Minimum stock must be 250–1000000 whole kits.');return n;};
    const minimumOverrides=field('Category stock minimums (request-word = kits, one per line)',el('textarea'));minimumOverrides.rows=4;minimumOverrides.placeholder='specialty = 54';minimumOverrides.value=Object.entries(config.categoryMinimumStock||{}).sort(([a],[b])=>a.localeCompare(b)).map(([slug,n])=>slug+' = '+n).join('\n');
    const stockMinimums=()=>{const result={};for(const line of minimumOverrides.value.split(/\n/).map(v=>v.trim()).filter(Boolean)){const match=line.match(/^([a-z][a-z0-9-]{0,47})\s*=\s*([0-9]{1,7})$/);if(!match||Number(match[2])>1000000||Object.hasOwn(result,match[1]))throw Error('Use distinct category = minimum lines, with 0–1000000 whole kits.');result[match[1]]=Number(match[2]);}if(Object.keys(result).length>128)throw Error('Select up to 128 category minimums.');return result;};
    body.append(el('p','hint','Optional per-category stock floors replace the default. These use confirmed complete kits per variant; estimates do not count. Save kitbot settings to apply. Empty lines use the default. Project users keep their lower floor when applicable.'));

    const limitedCategories=field('Limited categories (request-word = cooldown seconds, one per line)',el('textarea'));limitedCategories.rows=3;limitedCategories.placeholder='specialty = 86400';limitedCategories.value=Object.entries(config.categoryCooldownSeconds||{}).sort(([a],[b])=>a.localeCompare(b)).map(([slug,n])=>slug+' = '+n).join('\n');
    const categoryCooldowns=()=>{const result={};for(const line of limitedCategories.value.split(/\n/).map(v=>v.trim()).filter(Boolean)){const match=line.match(/^([a-z][a-z0-9-]{0,47})\s*=\s*([1-9][0-9]{0,6})$/);if(!match||Number(match[2])>2592000||Object.hasOwn(result,match[1]))throw Error('Use distinct category = seconds lines, with 1–2592000 whole seconds.');result[match[1]]=Number(match[2]);}if(Object.keys(result).length>128)throw Error('Select up to 128 limited categories.');return result;};
    body.append(el('p','hint','Limited categories share one kit per cooldown across everyone, including paid, gifted and whitelist orders. The timer starts at confirmed handoff and survives restarts. In-flight or uncertain deliveries reserve the slot. A stock floor of 0 still requires a complete kit to be present.'));
    const categories=el('div','management-section');body.append(categories);let catalog,stash;
    const operation=async fn=>{if(!writable())return;try{await fn();message.textContent='Saved.';}catch(e){message.textContent=e.message;}};
    const load=async()=>{
      const selected=new Set(additional.options.length?Array.from(additional.selectedOptions,o=>o.value):(config.sources||[]));
      stash=(snapshot.stashes||[]).find(s=>stashKey(s)===source.value);additional.replaceChildren();
      for(const s of snapshot.stashes||[])if(stash&&s.crew===stash.crew&&s.scope===stash.scope&&s.name!==stash.name){const o=el('option','',s.name);o.value=s.name;o.selected=selected.has(s.name);additional.append(o);}
      categories.replaceChildren();if(!stash){categories.append(el('p','hint','Define a source stash first.'));return;}
      catalog=await api('control',{op:'kit-categories-get',crew:stash.crew,scope:stash.scope,source:stash.name});
      const select=el('select');for(const c of Object.values(catalog.categories)){const o=el('option','',c.slug+' · '+c.name+' · '+(c.usage||'Both'));o.value=c.id;select.append(o);}select.append(Object.assign(el('option','','New category'),{value:''}));
      const slug=el('input'),name=el('input'),mode=el('select'),members=el('textarea');members.rows=4;mode.append(Object.assign(el('option','','Allowed contents only'),{value:'Items'}),Object.assign(el('option','','Selected kit IDs'),{value:'Kits'}));
      const usage=el('select');for(const value of ['Storage','Kitbot','Both'])usage.append(Object.assign(el('option','',value),{value}));
      const fill=()=>{const c=catalog.categories[select.value]||{slug:'',name:'',mode:'Kits',members:[],usage:'Both'};slug.value=c.slug;name.value=c.name;mode.value=c.mode;members.value=c.members.join('\n');usage.value=c.usage||'Both';};select.addEventListener('change',fill);fill();
      for(const [title,input] of [['Category',select],['Used for',usage],['Request word / storage slug (lowercase)',slug],['Display name',name],['Rule',mode],['Item IDs or kit UUIDs, one per line',members]]){const l=el('label','',title);input.setAttribute('aria-label',title);l.append(input);categories.append(l);}
      categories.append(el('p','hint','Storage groups organize columns; Kitbot categories are request words. A kit may match one of each. Both preserves shared behavior. Existing column reservations remain unchanged.'));
      categories.append(button('Save category',()=>operation(async()=>{await api('control',{op:'kit-categories-save',crew:stash.crew,scope:stash.scope,expected:catalog.revision,category:{id:select.value||crypto.randomUUID(),slug:slug.value,name:name.value,mode:mode.value,usage:usage.value,members:members.value.split(/\s+/).filter(Boolean)}});await load();}),!writable()),button('Categorize stocked kits',()=>operation(async()=>{await api('control',{op:'kit-categories-seed',crew:stash.crew,scope:stash.scope,source:stash.name,minimumStock:stockMinimum()});await load();}),!writable()));
      const list=el('div'),floors=stockMinimums();for(const [id,k] of Object.entries(catalog.stock||{})){const matches=Object.values(catalog.categories).filter(c=>(!c.usage||c.usage==='Both'||c.usage==='Kitbot')&&(c.mode==='Kits'?c.members.includes(id):Object.keys(k.items||{}).length&&Object.entries(k.items).every(([item,n])=>c.members.includes(item)&&n>0)));const required=matches.length?Math.min(...matches.map(c=>floors[c.slug]??stockMinimum())):stockMinimum();list.append(el('p','hint',(k.name||k.item)+' · '+k.complete+' complete · minimum '+required+(k.complete<=0||k.complete<required?' · OUT OF STOCK':'')+' · '+id));}categories.append(el('details'));categories.lastChild.append(el('summary','','Kit IDs & source stock'),list);
    };
    source.addEventListener('change',()=>operation(load));await load();
    const publicAccess=field('Public access (any verified whisper sender)',el('input'));publicAccess.type='checkbox';publicAccess.checked=!!config.publicAccess;
    body.append(el('p','hint','Public users get one free kit per hour. Credit purchases have a separate ten-minute cooldown; the free allowance stays hourly. Free claims never spend earned credits. Updated couriers verify sender UUIDs using the server player list. While public access is on, whitelisted project users have no hourly cooldown and normally need 109+ confirmed kits, with lower category floors honored; their configured request cap still applies. Leave public access off for whitelist-only service.'));
    const enabled=field('Enable whisper kitbot',el('input'));enabled.type='checkbox';enabled.checked=!!config.enabled;
    const worker=field('Courier / whisper receiver',el('select'));for(const w of snapshot.workers||[]){const o=el('option','',w.name||w.id);o.value=w.id;worker.append(o);}worker.value=config.worker||worker.value;
    const backupWorker=field('Second courier / whisper receiver (optional)',el('select'));backupWorker.append(Object.assign(el('option','','Disabled'),{value:''}));for(const w of snapshot.workers||[]){const o=el('option','',(w.name||w.id)+(w.connected?' · online':' · offline'));o.value=w.id;backupWorker.append(o);}backupWorker.value=config.backupWorker||'';
    body.append(el('p','hint','Both accounts share Mike, stock reservations, credits and user limits. Busy receivers route new deliveries to the other courier only when online, ready and idle in the same crew/server; otherwise orders queue. Each courier needs its own source homes and return bed/spawn.'));
    body.append(el('h3','','Mike · optional Ollama kit handler'),el('p','hint','Natural-language kit requests. Mike starts with public category names, sample item types and qualitative stock labels. On request, read-only inspection can show exact contents per kit and public variant references. Stored copy counts, stash names, locations, homes and job history stay on the host. Existing cooldowns and quantity caps still apply. Explicit kit/kits commands work without Ollama.'));
    const mikeConfig=config.mike||{},mikeEnabled=field('Enable Mike',el('input'));mikeEnabled.type='checkbox';mikeEnabled.checked=!!mikeConfig.enabled;
    const mikeEndpoint=field('Ollama base URL',el('input'));mikeEndpoint.type='url';mikeEndpoint.value=mikeConfig.endpoint||'https://ollama.com';
    const mikeModel=field('Ollama model (tool calling required)',el('input'));mikeModel.value=mikeConfig.model||'';mikeModel.placeholder='Cloud model ID or locally installed tag';
    const mikeProvenance=field('Public kit provenance',el('input'));mikeProvenance.maxLength=256;mikeProvenance.value=mikeConfig.provenance||'';mikeProvenance.placeholder='Optional public explanation of where the kits came from';
    const mikeCoordinateJokes=field('Fictional stash coordinate replies',el('input'));mikeCoordinateJokes.type='checkbox';mikeCoordinateJokes.checked=!!mikeConfig.coordinateJokes;
    const mikeKey=field('Ollama API key (blank keeps saved key)',el('input'));mikeKey.type='password';mikeKey.autocomplete='new-password';mikeKey.placeholder=mikeConfig.apiKeyConfigured?'API key saved':'Required for Ollama Cloud';
    const mikeClearKey=field('Clear saved Ollama API key',el('input'));mikeClearKey.type='checkbox';mikeClearKey.addEventListener('change',()=>{mikeKey.disabled=mikeClearKey.checked;if(mikeClearKey.checked)mikeKey.value='';});
    body.append(el('p','hint','Ollama Cloud uses https://ollama.com with its native /api/chat API. The key stays on the host and is never returned here or included in model context.'));
    const mikeFields=()=>{mikeEndpoint.disabled=!mikeEnabled.checked;mikeModel.disabled=!mikeEnabled.checked;};mikeEnabled.addEventListener('change',mikeFields);mikeFields();
    if(settings.mikeStatus)body.append(el('p','hint','Mike requests pending: '+settings.mikeStatus.pending+(settings.mikeStatus.error?' · '+settings.mikeStatus.error:'')));
    const whitelist=el('div','management-section'),people=[];body.append(el('h3','','Whitelisted users'),el('p','hint','In whitelist-only mode, each person has their own cooldown and free-order cap (default five kits). Credit orders allow up to 27 kits per delivery. In public mode, whitelist entries grant project access: no hourly cooldown, normally 109+ confirmed kits (lower category floors apply). New users default to one hour. Set 0 to disable their cooldown; the five-second duplicate guard still applies. Zero-cooldown users can order for different recipients concurrently; duplicate deliveries to the same recipient remain blocked.'),whitelist);
    const addPerson=person=>{
      const row=el('div','management-section'),name=el('input'),uuid=el('input'),hours=el('input'),cap=el('input'),role=el('select');name.value=person.name||'';uuid.value=person.uuid||'';hours.type='number';hours.min=0;hours.max=72;hours.step='any';hours.value=(person.cooldownSeconds??config.cooldownSeconds??3600)/3600;cap.type='number';cap.min=1;cap.max=36;cap.step=1;cap.value=person.maxKits??5;for(const value of ['User','Administrator']){const option=el('option','',value);option.value=value;role.append(option);}role.value=person.role||'User';role.setAttribute('aria-label','Role');
      for(const [title,input] of [['Minecraft username',name],['Player UUID',uuid],['User cooldown (hours; 0 disables)',hours],['Maximum free kits per request',cap]]){const label=el('label','',title);label.append(input);row.append(label);}
      const label=el('label','','Role');label.append(role);row.append(label);const entry={row,name,uuid,hours,cap,role};people.push(entry);row.append(button('Remove user',()=>{people.splice(people.indexOf(entry),1);row.remove();},!writable()));whitelist.append(row);
    };
    for(const person of config.whitelist||[])addPerson(person);
    body.append(button('Add whitelisted user',()=>addPerson({cooldownSeconds:3600}),!writable()));
    body.append(button('Save kitbot settings',()=>operation(async()=>{if(!stash)throw Error('Choose a source stash.');if(backupWorker.value&&backupWorker.value===worker.value)throw Error('Choose two different couriers.');const seconds=input=>{const n=Number(input.value)*3600;if(input.value===''||!Number.isInteger(n)||n<0||n>259200)throw Error('Cooldown must be 0–72 hours, precise to whole seconds.');return n;};const lines=people.map(p=>{const maxKits=Number(p.cap.value);if(!Number.isInteger(maxKits)||maxKits<1||maxKits>36)throw Error('Maximum kits must be 1–36 whole kits.');return {name:p.name.value.trim(),uuid:p.uuid.value.trim(),cooldownSeconds:seconds(p.hours),maxKits,role:p.role.value};});await api('control',{op:'kitbot-save',expected:settings.revision,config:{enabled:enabled.checked,publicAccess:publicAccess.checked,crew:stash.crew,scope:stash.scope,source:stash.name,worker:worker.value,backupWorker:backupWorker.value,minimumStock:stockMinimum(),categoryMinimumStock:stockMinimums(),categoryCooldownSeconds:categoryCooldowns(),sources:Array.from(additional.selectedOptions,o=>o.value),cooldownSeconds:3600,whitelist:lines,mike:{enabled:mikeEnabled.checked,endpoint:mikeEndpoint.value.trim(),model:mikeModel.value.trim(),provenance:mikeProvenance.value.trim(),coordinateJokes:mikeCoordinateJokes.checked,apiKey:mikeKey.value.trim(),clearApiKey:mikeClearKey.checked}}});dialog.close();}),!writable()),message);
    const receipts=el('details');receipts.append(el('summary','','Request receipts'));for(const r of Object.values(settings.requests||{}).reverse().slice(0,30))receipts.append(el('p','hint',r.name+(r.recipientName&&r.recipientName.toLowerCase()!==r.name.toLowerCase()?' → '+r.recipientName:'')+' · '+r.category+' × '+r.count+' · '+r.status+' · '+new Date(r.at).toLocaleString()+(r.detail?' · '+r.detail:'')));body.append(receipts);
  }catch(e){body.append(el('p','error',e.message));}
  body.append(button('Close',()=>dialog.close()));
}

async function presetLibrary() {
  const dialog=el('dialog'),body=el('div','management-section');dialog.append(body);document.body.append(dialog);
  dialog.addEventListener('close',()=>dialog.remove());dialog.showModal();
  const message=el('p','hint');
  try {
    const list=await api('control',{op:'workflow-list'}),catalog=await resource('GET','configuration-controls');
    const selected=el('select'),name=el('input'),folder=el('input'),setting=el('select'),active=el('select'),value=el('input');value.type='number';
    for(const r of list.workflows){const o=el('option','',(r.builtin?'Built-in · ':'Custom · ')+r.folder+' / '+r.name);o.value=r.id;selected.append(o);}
    for(const c of catalog.controls){const o=el('option','',c.label);o.value=c.id;setting.append(o);}
    for(const state of ['On','Off'])active.append(el('option','',state));
    body.append(el('h2','','Job presets'),el('p','hint','Future jobs only. Built-ins are read-only; duplicate first. Running jobs remain unchanged.'));
    const field=(title,input)=>{const l=el('label','',title);l.append(input);body.append(l);};
    field('Preset',selected);field('Name',name);field('Folder',folder);
    let record,draft=null,revision=0;
    const change=()=>{revision++;draft=null;};
    const load=async()=>{change();const current=revision;const r=await api('control',{op:'workflow-get',id:selected.value});if(current!==revision)return;record=r;name.value=r.name;folder.value=r.folder;message.textContent=r.builtin?'Duplicate this built-in to edit it.':'Custom preset ready.';};
    const operation=async fn=>{try{await fn();}catch(e){message.textContent=e.message;}};
    selected.addEventListener('change',()=>operation(load));
    body.append(button('Start job from this preset',()=>{dialog.close();openJob({preset:selected.value});},!writable()));
    body.append(button('Duplicate',()=>operation(async()=>{if(!record||record.id!==selected.value)return;await api('control',{op:'workflow-duplicate',source:record.id,id:crypto.randomUUID(),name:name.value+' copy',folder:folder.value});dialog.close();await presetLibrary();})),button('Rename / move',()=>operation(async()=>{if(!record||record.id!==selected.value)return;await api('control',{op:'workflow-save',id:record.id,name:name.value,folder:folder.value,package:record.package});await load();})));
    field('Guided control',setting);field('Proposed activation',active);field('Proposed value (not a current reading)',value);
    const update=()=>{change();const c=catalog.controls.find(c=>c.id===setting.value);value.parentElement.hidden=!c.setting;value.min=c.min;value.max=c.max;value.step=c.step||1;value.value=c.example;message.textContent=c.help;};
    setting.addEventListener('change',update);active.addEventListener('change',change);value.addEventListener('input',change);update();
    const preview=el('pre');
    body.append(button('Preview change',()=>operation(async()=>{change();const rev=revision,request={id:selected.value,control:setting.value,active:active.value==='On',value:Number(value.value)};const p=await api('control',{op:'workflow-preview-control',...request});if(rev!==revision)return;draft={op:'workflow-edit-control',...request,expected:p.expected};preview.textContent=p.description+'\n'+p.scope+'\n'+JSON.stringify({before:p.before,after:p.after},null,2);})),button('Save reviewed change',()=>operation(async()=>{if(!draft)throw Error('Preview the change first.');const request=draft;change();await api('control',request);await load();message.textContent='Saved for future jobs. Running jobs unchanged.';})),preview,message,button('Close',()=>dialog.close()));
    const details=jsonDetails('Captured package',{});body.append(details);
    await load();details.replaceChildren(el('summary','','Captured package'),el('pre','',JSON.stringify(record?.package,null,2)));
    selected.addEventListener('change',()=>{details.replaceChildren(el('summary','','Captured package · select Copy to inspect'));});
    body.append(button('Show current captured package',()=>operation(async()=>{const r=await api('control',{op:'workflow-get',id:selected.value});details.replaceChildren(el('summary','','Captured package'),el('pre','',JSON.stringify(r.package,null,2)));details.open=true;})));
  } catch(e){body.append(el('p','error',e.message),button('Close',()=>dialog.close()));}
}
const rememberedFields=['job-kind','job-name','job-crew','job-priority','job-preset','highway-direction','highway-length','follow-radius'];
function rememberLaunch(){lastLaunch=Object.fromEntries(rememberedFields.map(id=>[id,$(id).value]));}
async function openJob(context={}) {
  if (!writable()) return;
  $('job-error').textContent = pendingSubmission ? 'Submission outcome is uncertain. Retry the same request or inspect Jobs; editing is locked to avoid duplicate work.' : '';
  reviewedSubmission=null;$('job-preview').hidden=true;
  $('job-fields').disabled = !!pendingSubmission; $('job-submit').textContent = pendingSubmission ? 'Retry same submission ↗' : 'Preview job';
  if (!pendingSubmission) {
    scanStash=context.stash?{...context.stash,id:context.id}:null;
    kitContext=context.kitStash?context:null;$('kit-source').replaceChildren();$('kit-type').replaceChildren();$('kit-types').replaceChildren();$('kit-field').dataset.entry='';
    for(const axis of ['x','y','z'])$('highway-'+axis).value='';
    $('job-crew').replaceChildren(); snapshot.crews.forEach(crew => { const option = el('option', '', crew); option.value = crew; $('job-crew').append(option); });
    if(lastLaunch)for(const id of rememberedFields.filter(id=>id!=='job-preset'))if($(id).tagName!=='SELECT'||[...$(id).options].some(o=>o.value===lastLaunch[id]))$(id).value=lastLaunch[id];
    if(context.preset){$('job-kind').value='preset';$('preset-field').hidden=false;$('highway-field').hidden=true;$('kit-field').hidden=true;}
    if(context.crew)$('job-crew').value=snapshot.crews.includes(context.crew)?context.crew:snapshot.workers.find(w=>w.scope===context.kitStash?.scope&&w.connected)?.crew||snapshot.crews[0];
    updateWorkers(); updateSource();
    if(context.worker){for(const input of $('job-workers').querySelectorAll('input'))input.checked=input.value===context.worker;updateScope();}
  }
  $('job-fields').disabled=true;$('job-submit').disabled=true;$('job-dialog').showModal();
  if(!pendingSubmission){let loaded=false;
    try {
      presetList=scanStash?[]:(await api('control',{op:'workflow-list'})).workflows;$('job-preset').replaceChildren();
      for(const p of presetList){const o=el('option','',p.folder+' / '+p.name);o.value=p.id;$('job-preset').append(o);}
      if(context.preset&&!presetList.some(p=>p.id===context.preset))throw Error('Requested job preset is unavailable. Refresh the host and try again.');
      const preferred=context.preset||lastLaunch?.['job-preset'];
      if(preferred&&presetList.some(p=>p.id===preferred))$('job-preset').value=preferred;
      if(context.preset)$('job-kind').value='preset';
      if(scanStash){
        $('job-kind').value='stash';
        const [server,dimension]=scanStash.scope.split('\n');$('job-server').value=server;$('job-dimension').value=dimension;
      }
      updateSource();useWorkerPosition();
      if(scanStash)$('job-name').value='Scan '+scanStash.name;
      else if(kitContext){const [server,dimension]=kitContext.kitStash.scope.split('\n');$('job-server').value=server;$('job-dimension').value=dimension;updateKitSources();}
      else if(lastLaunch&&!context.preset)$('job-name').value=lastLaunch['job-name'];
      loaded=true;
    }catch(e){$('job-error').textContent=e.message;}
    finally{$('job-fields').disabled=!loaded;$('job-submit').disabled=!loaded;}
  }
}
$('new-job').addEventListener('click',()=>openJob());
$('job-dialog').addEventListener('close',()=>{if(!pendingSubmission&&!scanStash)rememberLaunch();});
function updateWorkers() {
  $('job-workers').replaceChildren(); const crew = $('job-crew').value;
  const workers = snapshot.workers.filter(w => w.crew === crew && w.connected && w.reconciled && (!scanStash || w.scope === scanStash.scope) && (!kitContext || w.scope === kitContext.kitStash.scope));
  for (const worker of workers) {
    const label = el('label', 'worker-option'); const checkbox = el('input'); checkbox.type = 'checkbox'; checkbox.value = worker.id; checkbox.checked = true; checkbox.addEventListener('change', updateScope);
    const text = el('span', '', worker.name); text.append(el('small', '', ' · ' + workerState(worker).status)); label.append(checkbox, text); $('job-workers').append(label);
  }
  $('follow-leader').replaceChildren();for(const w of workers){const o=el('option','',w.name);o.value=w.id;$('follow-leader').append(o);}
  if(!$('follow-field').hidden)excludeLeader();
  if (!workers.length) $('job-workers').append(el('p', 'hint', 'No connected, reconciled workers in this crew.')); updateScope();
}
function selectedWorkers() { return [...$('job-workers').querySelectorAll('input:checked')].map(n => snapshot.workers.find(w => w.id === n.value)).filter(Boolean); }
function useWorkerPosition() {
  const worker=selectedWorkers()[0];if(!worker||!worker.positionFresh||!['x','y','z'].every(axis=>Number.isFinite(worker[axis])))return false;
  for(const axis of ['x','y','z'])$('highway-'+axis).value=Math.floor(worker[axis]);return true;
}
function updateScope() { const worker = selectedWorkers()[0]; if (worker && !scanStash) { const [server, dimension] = worker.scope.split('\n'); $('job-server').value = server; $('job-dimension').value = dimension; if($('job-kind').value==='highway')useWorkerPosition(); } if(!$('kit-field').hidden)updateKitSources(); }
function kitSources(){
  const scope=$('job-server').value.trim()+'\n'+$('job-dimension').value.trim(),crew=$('job-crew').value,sources=new Map();
  for(const stash of snapshot.stashes||[])if(stash.scope===scope&&(stash.crew===crew||stash.crew==='Local')&&(!sources.has(stash.name)||stash.crew===crew))sources.set(stash.name,stash);
  return [...sources.values()];
}
function kitDestinations(){
  const server=$('job-server').value.trim(),crew=$('job-crew').value,destinations=new Map(),team=$('kit-field').dataset.entry==='task-stash-transfer-team';
  for(const stash of snapshot.stashes||[])if(stash.scope.split('\n')[0]===server&&(stash.crew===crew||stash.crew==='Local')&&(!team||stash.scope===server+'\n'+$('job-dimension').value.trim())){
    const key=JSON.stringify([stash.scope,stash.name]);if(!destinations.has(key)||stash.crew===crew)destinations.set(key,stash);
  }
  return [...destinations].map(([key,stash])=>({key,stash}));
}
function updateKitSources(){
  if($('kit-field').hidden)return;
  const selected=$('kit-source').value||kitContext?.kitStash.name||'',sources=kitSources();$('kit-source').replaceChildren();
  for(const stash of sources){const option=el('option','',stash.name+' · '+Object.keys(stash.kitTypes||{}).length+' kit types');option.value=stash.name;$('kit-source').append(option);}
  if(sources.some(stash=>stash.name===selected))$('kit-source').value=selected;
  const recipient=$('kit-recipient-worker'),previous=recipient.value,manual=el('option','','Enter another player manually');manual.value='';recipient.replaceChildren(manual);
  for(const worker of snapshot.workers.filter(worker=>worker.crew===$('job-crew').value&&worker.scope===$('job-server').value.trim()+'\n'+$('job-dimension').value.trim()&&worker.connected&&worker.reconciled&&worker.id!==selectedWorkers()[0]?.id)){const option=el('option','',worker.name);option.value=worker.id;recipient.append(option);}
  if([...recipient.options].some(option=>option.value===previous))recipient.value=previous;
  updateKitTypes();
  refreshKitRoutes();
}
function routeChoices(select,choices){
  const previous=select.value,items=choices.map(([value,label])=>({value,label}));
  if(previous&&!items.some(item=>item.value===previous))items.push({value:previous,label:'Unavailable · '+previous});
  if(JSON.stringify(items)!==select.dataset.choices){select.replaceChildren();for(const item of items){const option=el('option','',item.label);option.value=item.value;select.append(option);}select.dataset.choices=JSON.stringify(items);}
  if(previous)select.value=previous;
}
function refreshKitRoutes(){
  const sources=kitSources();
  for(const row of $('kit-routes').children){
    const [source,kit,target]=row.querySelectorAll('select');
    routeChoices(source,sources.map(stash=>[stash.name,stash.name]));
    const stash=sources.find(stash=>stash.name===source.value);
    routeChoices(kit,Object.entries(stash?.kitTypes||{}).map(([id,type])=>[id,type.name||type.item]));
    routeChoices(target,sources.filter(stash=>stash.name!==source.value).map(stash=>[stash.name,stash.name]));
  }
  $('kit-route-add').disabled=$('kit-routes').children.length>=16;
}
function addKitRoute(){
  if($('kit-routes').children.length>=16)return;
  const row=el('div','form-grid'),selects=[];
  for(const labelText of ['Source stash','Kit type','Destination stash']){const field=el('label','',labelText),select=el('select');field.append(select);row.append(field);selects.push(select);}
  const remove=el('button','','Remove route');remove.type='button';remove.addEventListener('click',()=>{row.remove();refreshKitRoutes();});row.append(remove);
  selects[0].addEventListener('change',()=>{selects[1].value='';selects[2].value='';refreshKitRoutes();});$('kit-routes').append(row);refreshKitRoutes();
}
function readKitRoutes(){
  const sources=kitSources(),routes=[...$('kit-routes').children].map(row=>{const [source,kit,target]=row.querySelectorAll('select');return {name:source.value,kitTypeId:kit.value,targetStashName:target.value};});
  if(routes.length<2||routes.length>16)throw Error('Choose 2–16 Round Robin routes.');
  const seen=new Set(),destinations=new Map();
  for(const route of routes){
    if(!sources.find(stash=>stash.name===route.name)?.kitTypes?.[route.kitTypeId]||!sources.some(stash=>stash.name===route.targetStashName)||route.name===route.targetStashName)throw Error('Every route needs a mapped source, known kit, and different destination in this world.');
    const key=JSON.stringify(route);if(seen.has(key))throw Error('Remove duplicate routes.');seen.add(key);
    if(destinations.has(route.kitTypeId)&&destinations.get(route.kitTypeId)!==route.targetStashName)throw Error('Routes for the same kit must share a destination for safe carried-kit recovery.');destinations.set(route.kitTypeId,route.targetStashName);
  }
  return routes;
}
function updateKitTypes(){
  const source=kitSources().find(stash=>stash.name===$('kit-source').value),selected=$('kit-type').value||kitContext?.kitTypeId||'';$('kit-type').replaceChildren();
  const previous=new Set([...$('kit-types').selectedOptions].map(option=>option.value));$('kit-types').replaceChildren();
  for(const [id,type] of Object.entries(source?.kitTypes||{})){const counts=source.kitCounts?.[id]||{},option=el('option','',(type.name||type.item)+' · '+number(counts.complete)+' complete / '+number(counts.incomplete)+' incomplete');option.value=id;$('kit-type').append(option);}
  for(const option of $('kit-type').options){const copy=option.cloneNode(true);copy.selected=previous.has(copy.value);$('kit-types').append(copy);}
  if([...$('kit-type').options].some(option=>option.value===selected))$('kit-type').value=selected;
  const target=$('kit-target').value;$('kit-target').replaceChildren();for(const {key,stash} of kitDestinations().filter(({stash})=>stash.name!==source?.name||stash.scope!==source?.scope)){const option=el('option','',stash.name+' · '+stash.scope.split('\n')[1]);option.value=key;$('kit-target').append(option);}if([...$('kit-target').options].some(option=>option.value===target))$('kit-target').value=target;
  $('kit-hint').textContent=source?($('kit-field').dataset.entry==='task-stash-transfer-team'?'Choose 2–3 workers. ':'Choose one worker. ')+'Counts are directly observed boxes; unscanned upper chests are not guaranteed.':'No stash with this crew and world is mapped. Define or export one first.';
}
function updateKitDestination(){const active=!$('kit-field').hidden,team=active&&$('kit-field').dataset.entry==='task-stash-transfer-team',transfer=active&&['task-stash-transfer','task-stash-transfer-team'].includes($('kit-field').dataset.entry),round=transfer&&!team&&$('kit-mode').value==='RoundRobin',mixed=transfer&&!team&&$('kit-mode').value==='Mixed',player=active&&!transfer&&$('kit-destination').value==='Player';if(transfer){$('kit-destination').value='Stash';$('kit-all').checked=true;}$('kit-mode-field').hidden=!transfer||team;$('kit-source-fields').hidden=round;$('kit-types-select').hidden=round;$('kit-routes-field').hidden=!round;if(round&&!$('kit-routes').children.length){addKitRoute();addKitRoute();}$('kit-destination').disabled=$('kit-all').disabled=transfer;$('kit-types-field').hidden=!transfer;$('kit-team-field').hidden=!team;$('kit-deposit-carried').disabled=team||mixed;if(team||mixed)$('kit-deposit-carried').checked=false;$('kit-type').parentElement.hidden=transfer;$('kit-recipient-field').hidden=!player;$('kit-target-field').hidden=round||!active||$('kit-destination').value!=='Stash';$('kit-recipient-name').required=$('kit-recipient-id').required=player;if($('kit-destination').value!=='Stash')$('kit-all').checked=false;$('kit-count-label').textContent=team?'Boxes per worker per trip':$('kit-all').checked?'Boxes per trip':'Boxes';}
$('kit-mode').addEventListener('change',()=>{updateKitDestination();$('kit-ender-transport').disabled=$('kit-mode').value!=='Drain';if($('kit-ender-transport').disabled)$('kit-ender-transport').checked=false;});$('kit-route-add').addEventListener('click',addKitRoute);
$('kit-all').addEventListener('change',()=>{if($('kit-all').checked){$('kit-destination').value='Stash';$('kit-count').value='36';}updateKitDestination();});
$('kit-source').addEventListener('change',updateKitTypes);$('kit-destination').addEventListener('change',updateKitDestination);
$('kit-recipient-worker').addEventListener('change',()=>{const worker=snapshot.workers.find(worker=>worker.id===$('kit-recipient-worker').value);if(worker){$('kit-recipient-name').value=worker.name;$('kit-recipient-id').value=worker.id;}});
function updateSource() {
  const kind = $('job-kind').value,preset=presetList.find(p=>p.id===$('job-preset').value),highway=kind==='highway'||kind==='preset'&&preset?.highway,follow=kind==='preset'&&['task-follow','task-bodyguard','task-crystal-guard'].includes(preset?.entry),kit=kind==='preset'&&['task-kit-delivery','task-kit-remove-incomplete','task-stash-transfer','task-stash-transfer-team'].includes(preset?.entry);
  $('preset-field').hidden=kind!=='preset';$('follow-field').hidden=!follow;$('kit-field').hidden=!kit;$('highway-field').hidden=!highway;$('package-field').hidden=kind!=='package';$('lua-field').hidden=kind!=='lua';$('args-field').hidden=highway||follow||kit;
  if(kit&&$('kit-field').dataset.entry!==preset.entry){const team=preset.entry==='task-stash-transfer-team',transfer=team||preset.entry==='task-stash-transfer';$('kit-destination').value=transfer?'Stash':preset.entry==='task-kit-remove-incomplete'?'Carry':'Player';$('kit-count').value=transfer?'36':'1';$('kit-all').checked=transfer;$('kit-include-incomplete').checked=$('kit-deposit-carried').checked=false;const boxes=$('job-workers').querySelectorAll('input');boxes.forEach((box,index)=>{box.checked=team?index<3:index===0;});$('kit-field').dataset.entry=preset.entry;updateScope();updateKitSources();}updateKitDestination();
  $('crystal-target-field').hidden=preset?.entry!=='task-crystal-guard';
  for(const input of $('highway-field').querySelectorAll('input'))input.required=highway;
  if(kind==='preset'){if(preset)$('job-name').value=preset.name;if(preset?.entry==='task-travel'){const w=selectedWorkers()[0];$('job-args').value=JSON.stringify({x:Math.floor(w?.x||0),y:Math.floor(w?.y??116),z:Math.floor(w?.z||0),radius:2});}if(preset?.entry==='task-wait')$('job-args').value='{"ticks":200}';}
  if(follow)excludeLeader();
  if (!$('job-script').value) $('job-script').value = WAIT;
  if(kind==='highway') { if(!$('job-name').value||['Highway job','Inspect stash'].includes($('job-name').value))$('job-name').value='6b6t highway';useWorkerPosition(); }
  if(kind==='stash') { $('job-name').value='Inspect stash'; if($('job-args').value==='{}')$('job-args').value=JSON.stringify({name:'Main stash',minX:0,maxX:15,minY:116,maxY:120,minZ:0,maxZ:15}); }
  if(scanStash){$('args-field').hidden=true;for(const id of ['job-kind','job-name','job-crew','job-server','job-dimension'])$(id).disabled=true;}
  else for(const id of ['job-kind','job-name','job-crew','job-server','job-dimension'])$(id).disabled=false;
}
function excludeLeader(){for(const box of $('job-workers').querySelectorAll('input'))if(box.value===$('follow-leader').value)box.checked=false;}
$('follow-leader').addEventListener('change',excludeLeader);
$('job-preset').addEventListener('change',updateSource);
$('job-fields').addEventListener('input',()=>{reviewedSubmission=null;$('job-preview').hidden=true;$('job-submit').textContent='Preview job';});
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
  let scanJobId;
  busy=true;$('job-fields').disabled=true;$('job-submit').disabled=true;
  try {
    if (!pendingSubmission && reviewedSubmission) {pendingSubmission=reviewedSubmission;reviewedSubmission=null;}
    if (!pendingSubmission) {
      const workers = selectedWorkers(); if (!workers.length) throw new Error('Select at least one connected worker.');
      const scope = $('job-server').value.trim() + '\n' + $('job-dimension').value.trim();
      if (workers.some(w => w.scope !== scope)) throw new Error('Selected workers must be on the specified server and dimension.');
      const crew=await resource('GET','workers/'+workers[0].id);
      if(crew.crew!==$('job-crew').value||!crew.connected)throw new Error('Crew membership changed. Refresh the roster and preview again.');
      const kind = $('job-kind').value; if (kind === 'package' && !packaged) throw new Error('Choose an exported workflow package first.');
      const common={id:crypto.randomUUID(),name:$('job-name').value.trim(),crewId:crew.crewId,workerIds:workers.map(w=>w.id),scope:{server:$('job-server').value.trim(),dimension:$('job-dimension').value.trim()},priority:Number($('job-priority').value)};
      if(scanStash){
        if(scope!==scanStash.scope)throw new Error('Stash world changed. Refresh the stash list before retrying.');
        pendingSubmission={...common,stashId:scanStash.id,bounds:scanStash.bounds};
      } else if(kind==='highway'||kind==='preset') {
        const preset=kind==='highway'?{id:'highway-default',highway:true}:presetList.find(p=>p.id===$('job-preset').value);
        if(!preset)throw Error('Choose a preset.');
        const kit=['task-kit-delivery','task-kit-remove-incomplete','task-stash-transfer','task-stash-transfer-team'].includes(preset.entry),team=preset.entry==='task-stash-transfer-team',transfer=team||preset.entry==='task-stash-transfer';
        let args=preset.highway||kit||['task-follow','task-bodyguard','task-crystal-guard'].includes(preset.entry)?{}:JSON.parse($('job-args').value);
        if(preset.highway){if(workers.length>3)throw Error('Native highway jobs support at most three workers.');args={direction:$('highway-direction').value,length:Number($('highway-length').value),x:Number($('highway-x').value),y:Number($('highway-y').value),z:Number($('highway-z').value)};}
        if(kit){
          if(team?(workers.length<2||workers.length>3):workers.length!==1)throw Error(team?'Choose two or three workers for a crew transfer.':'Choose exactly one worker for a kit job.');
          const source=kitSources().find(stash=>stash.name===$('kit-source').value),count=Number($('kit-count').value),destination=$('kit-destination').value,kitTypeId=$('kit-type').value;
          if(!Number.isInteger(count)||count<1||count>36)throw Error('Choose 1–36 kit boxes.');
          if(transfer&&!team&&$('kit-mode').value==='RoundRobin')args={routes:readKitRoutes(),count,includeIncomplete:$('kit-include-incomplete').checked,depositCarried:$('kit-deposit-carried').checked};
          else{
          if(!source||source.scope!==scope||!transfer&&!source.kitTypes?.[kitTypeId])throw Error('Choose a mapped stash and one of its known kit types in this world.');
          args={...source.bounds,name:source.name,kitTypeId,count,destination,transferAll:$('kit-all').checked,includeIncomplete:$('kit-include-incomplete').checked};
          if(transfer){delete args.kitTypeId;const ids=[...$('kit-types').selectedOptions].map(option=>option.value);if(ids.length)args.kitTypeIds=ids;args.depositCarried=$('kit-deposit-carried').checked;if(!team&&$('kit-mode').value==='Mixed'){if(ids.length<2||ids.length>16)throw Error('Select 2–16 kit types for mixed loads.');args.mixedLoads=true;args.depositCarried=false;}}
          if(transfer&&!team&&$('kit-mode').value==='Drain'&&$('kit-ender-transport').checked)args.enderTransport=true;
          if(team){args.leader=workers[0].id;args.tpaWarmupTicks=Math.round(Number($('kit-tpa-warmup').value)*20);if(!Number.isInteger(args.tpaWarmupTicks)||args.tpaWarmupTicks<0||args.tpaWarmupTicks>72000)throw Error('Choose a TPA warmup between 0 and 3600 seconds.');}
          if(destination==='Player'){args.recipient=$('kit-recipient-id').value.trim();args.recipientName=$('kit-recipient-name').value.trim();args.returnHome=$('kit-return-home').checked;if(!/^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$/.test(args.recipient)||!/^[A-Za-z0-9_]{1,16}$/.test(args.recipientName))throw Error('Specify the recipient’s exact UUID and Minecraft username.');}
          if(destination==='Stash'){const target=kitDestinations().find(d=>d.key===$('kit-target').value)?.stash;if(!target||target.name===source.name&&target.scope===source.scope)throw Error('Choose another mapped stash.');args.targetStashName=target.name;if(target.scope!==source.scope)args.targetDimension=target.scope.split('\n')[1];}
          }
        }
        if(['task-follow','task-bodyguard','task-crystal-guard'].includes(preset.entry)){args={target:$('follow-leader').value,radius:Number($('follow-radius').value),ticks:0};if(workers.some(w=>w.id===args.target))throw Error('Uncheck the subject; select workers only.');if(preset.entry!=='task-follow'&&workers.length>3)throw Error('Bodyguard supports at most three workers.');if(preset.entry==='task-crystal-guard')args.combatTargets=$('crystal-targets').value.split(',').map(name=>name.trim()).filter(Boolean);}
        const packet=await api('control',{op:'workflow-prepare',id:preset.id,scope,args});
        pendingSubmission={...common,args,package:packet};
      } else {
        const args = JSON.parse($('job-args').value); if (!args || Array.isArray(args) || typeof args !== 'object') throw new Error('Workflow arguments must be a JSON object.');
        const script=kind === 'stash' ? STASH_SCAN : kind === 'wait' ? WAIT : $('job-script').value;
        const captured=kind === 'package' ? packaged : {version:1,entry:'main',programs:{main:{name:common.name,script}},profiles:{Current:{}},highways:{}};
        pendingSubmission = {...common,args,package:captured};
      }
      reviewedSubmission=pendingSubmission;pendingSubmission=null;$('job-preview-text').textContent=JSON.stringify(reviewedSubmission,null,2);$('job-preview').hidden=false;$('job-preview').open=true;$('job-submit').textContent='Dispatch reviewed job ↗';return;
    }
    busy = true; $('job-fields').disabled = true; $('job-submit').disabled = true; render();
    pendingSubmissionKey ||= crypto.randomUUID();
    const submission=pendingSubmission;
    const target=submission.stashId?'stashes/'+submission.stashId+'/scan':'jobs';
    const payload=submission.stashId?{id:submission.id,workerIds:submission.workerIds,priority:submission.priority}:submission;
    const job=await resource('POST',target,payload,pendingSubmissionKey);scanJobId=submission.stashId?job.id:null;
    pendingSubmission = pendingSubmissionKey = null; $('job-dialog').close(); notice('Job dispatched. Follow delivery and worker readiness in Jobs.', true);
  } catch (error) {
    const uncertain = pendingSubmission && error.uncertain;
    if (!uncertain) pendingSubmission = pendingSubmissionKey = null;
    $('job-fields').disabled = !!pendingSubmission; $('job-submit').textContent = pendingSubmission ? 'Retry same submission ↗' : 'Preview job';
    $('job-error').textContent = error.message + (pendingSubmission ? ' Outcome uncertain: retry uses the same command and job IDs. Inspect Jobs before creating replacement work.' : '');
  } finally { busy = false; $('job-fields').disabled=!!pendingSubmission;$('job-submit').disabled = false; await refresh(); if(scanJobId)inspect('job',scanJobId); }
});

$('demo').addEventListener('click', () => {
  generation++;
  clearToken(); demo = true; const now = Date.now();
  const workers = ['Atlas', 'AtlasBot', 'AtlasBot2'].map((name, i) => ({ id: 'demo-worker-' + i, name, crew: 'Highway', scope: 'example.invalid\nminecraft:the_nether', connected: true, reconciled: true, positionFresh: true, observationAgeMs: 120, x: i - 1, y: 116, z: -2048, current: 'demo-run-' + i }));
  const nativeWorkers = workers.map((w, i) => ({ ...w, phase: i === 2 ? 'resupplying' : 'building', status: i === 2 ? 'Recovering supply container; crew continues' : 'Healthy · paving and excavating', currentRow: 2048, verifiedBase: 2049, verifiedMask: 31, currentResolved: true, fresh: true, resourceCounts: { inventory: { obsidian: 512-i*64, pickaxes: 3, food: 48 }, enderChest: { obsidian: 1728, pickaxes: 9, food: 128 }, total: { obsidian: 2240-i*64, pickaxes: 12, food: 176 }, enderChestKnown: true } }));
  const task = { id: 'demo-job', name: 'Northbound · the long road', crew: 'Highway', workflowName: 'Highway Builder', status: 'Running', priority: 0, server: 'example.invalid', dimension: 'minecraft:the_nether', highwayProgress: 2048, nativeDefinition: { length: 100000 }, detail: 'Paving verified. Detached supplier returning; remaining workers keep building.', runs: Object.fromEntries(workers.map((w, i) => [w.id, { id: w.current, status: 'Running', detail: nativeWorkers[i].status }])) };
  snapshot = { status: 'Running', crews: ['Highway', 'Survey'], workers, tasks: [task], history: [], highways: { Highway: { phase: 'building', progress: 2048, length: 100000, execution: 'demo-execution', supplyOwner: workers[2].id, workers: nativeWorkers, resourceCounts: { total: { obsidian: 6528, pickaxes: 36, food: 528 }, workers: 3, enderChestKnown: true }, events: [{ time: now - 20000, detail: 'Worker detached for supplies. Remaining builders share the road.' }, { time: now - 5000, detail: 'Verified forward work window advanced.' }] }, Survey: { phase: 'idle', events: [] } } };
  refreshedAt = now; page = 'overview'; unlock();
});
