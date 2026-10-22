// Run: node host-service/src/test/webui-progress-check.mjs
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
const source = readFileSync(new URL('../main/resources/webui/app.js', import.meta.url), 'utf8');
const reserveBalance=runInNewContext(source.slice(source.indexOf('function kitReserveBalance('),source.indexOf('function kitReserveText('))+';kitReserveBalance;');
for(const [stash,expected] of [
  [{kitCounts:{a:{complete:1123,incomplete:999}},kitReserves:{a:500}},{floor:500,stock:1123,surplus:623,shortfall:0}],
  [{kitCounts:{a:{complete:1123}},transferKitCounts:{a:{complete:400}},kitReserves:{a:500}},{floor:500,stock:400,surplus:0,shortfall:100}],
  [{kitReserves:{a:500}},{floor:500,stock:0,surplus:0,shortfall:500}],
  [{kitCounts:{a:{complete:12}}},{floor:0,stock:12,surplus:12,shortfall:0}]
])assert.deepEqual(JSON.parse(JSON.stringify(reserveBalance(stash,'a'))),expected);
const kitFields=new Map();
const kitField=id=>{if(!kitFields.has(id))kitFields.set(id,{hidden:false,value:'',checked:true,disabled:false,dataset:{},children:[{},{}],parentElement:{hidden:false}});return kitFields.get(id);};
kitField('kit-field').dataset.entry='task-stash-transfer';kitField('kit-mode').value='Mixed';
const kitControls=runInNewContext(source.slice(source.indexOf('function updateKitDestination()'),source.indexOf("$('kit-mode').addEventListener"))+';updateKitDestination;',{$:kitField,addKitRoute(){throw Error('Mixed mode must not add routes');}});
kitControls();
assert.equal(kitField('kit-deposit-carried').disabled,true);assert.equal(kitField('kit-deposit-carried').checked,false);
assert.equal(kitField('kit-source-fields').hidden,false);assert.equal(kitField('kit-types-select').hidden,false);assert.equal(kitField('kit-routes-field').hidden,true);
kitField('kit-mode').value='RoundRobin';kitControls();
assert.equal(kitField('kit-deposit-carried').disabled,false);assert.equal(kitField('kit-source-fields').hidden,true);assert.equal(kitField('kit-routes-field').hidden,false);
const rules=[];
const sheet={cssRules:rules,insertRule(rule){rules.push(rule);}};
const chatColor=runInNewContext(source.slice(source.indexOf('const chatColors='),source.indexOf('function chatPanel('))+'\nchatColor;', {document:{styleSheets:[sheet]}});
assert.equal(chatColor('#55FFFF'),'chat-rgb-55ffff');
assert.equal(chatColor('#55ffff'),'chat-rgb-55ffff');
assert.equal(rules.length,1);
assert.ok(rules[0].includes('color:#55ffff'));
assert.equal(chatColor('red; background:url(evil)'), '');
assert.equal(chatColor(null),'');
const progress = source.slice(source.indexOf('function progress('), source.indexOf('function resourceLine('));
const el = (tag, cls, text) => ({ tag, cls, text, children: [], append(...nodes) { this.children.push(...nodes); }, setAttribute() {} });
for (const [value, total, percent] of [[50, 1000, '5.0%'], [1, 3, '33.3%'], [0, 0, '0.0%'], [1000, 1000, '100.0%']]) {
  const node = el('div');
  runInNewContext(progress + '\nprogress(node, value, total);', { el, number: String, node, value, total });
  assert.equal(node.children[0].children[0].text, percent);
  assert.equal(node.children[0].children[1].text, `${value} of ${total} blocks mined`);
}
console.log('WebUI progress checks passed.');
const progressText=runInNewContext(source.slice(source.indexOf('function actionProgressText('),source.indexOf('function inspectionShape('))+'\nactionProgressText;');
assert.match(progressText({state:'Recovering',phase:'Travel',expectedNextEvent:'Safe arrival',progressAgeMs:121000,recoveryAttempts:1}),/121s without progress.*1\/2 navigation retries/);
assert.match(progressText(null),/unavailable/);
assert.match(progressText({state:'Paused',phase:'Scan',expectedNextEvent:'Container contents',progressAgeMs:120000,recoveryAttempts:0,target:'1, 64, 2',closestDistance:6.2}),/target 1, 64, 2.*closest approach 6\.2 blocks/);

// Render and exercise the new safety controls with no host or game session.
const hostilesSource=source.slice(source.indexOf('function renderHostiles('),source.indexOf('function resourceLine('));
const hostileNodes=[],hostileContent={children:[],append(...nodes){this.children.push(...nodes);}};
const hostileEl=(tag,cls='',text='')=>{
  const node={tag,cls,text,dataset:{},children:[],handlers:{},append(...children){this.children.push(...children);},addEventListener(event,handler){this.handlers[event]=handler;}};
  hostileNodes.push(node);return node;
};
const hostileState={workers:[{name:'Updated',scope:'test.invalid\nminecraft:the_nether',connected:true,knownHostilesSupported:true},{name:'Older',connected:true}],knownHostiles:{enabled:false,players:[{id:'entry-id',name:'Enemy',server:'test.invalid',uuid:'',source:'manual',addedAt:1000}]}};
const hostileRequests=[],draft={server:'',name:'',uuid:''};let hostileWritable=true;
const hostileContext={snapshot:hostileState,hostileDraft:draft,safetyDraft:null,$:()=>hostileContent,el:hostileEl,writable:()=>hostileWritable,
  empty:(title,text)=>hostileEl('p','',title+' '+text),section(){},control:(request,confirmation)=>hostileRequests.push({request:structuredClone(request),confirmation}),
  button:(text,action,disabled)=>{const node=hostileEl('button','',text);node.disabled=disabled;node.handlers.click=action;return node;}};
const renderHostileTest=()=>{hostileNodes.length=0;hostileContent.children.length=0;runInNewContext(hostilesSource+'\nrenderHostiles();',hostileContext);};
renderHostileTest();
assert.equal(draft.server,'test.invalid');
assert.ok(hostileNodes.some(n=>n.cls==='error'&&n.text.includes('Older')));
assert.ok(hostileNodes.some(n=>n.cls==='error'&&n.text.includes('Local detection only')&&n.text.includes('Updated')));
hostileState.workers[0].hostileWarningsSupported=true;
hostileState.hostileWarnings=[{observerName:'Other',crew:'Elsewhere',server:'test.invalid',sighting:{player:{name:'Enemy',x:1,y:116,z:3}}}];
renderHostileTest();assert.ok(hostileNodes.some(n=>n.cls==='error'&&n.text.includes('Enemy seen by Other')&&n.text.includes('1, 116, 3')));
assert.ok(!hostileNodes.some(n=>n.cls==='error'&&n.text.includes('Local detection only')));
const nameInput=hostileNodes.find(n=>n.dataset.key==='hostile-name');nameInput.value='ManualEnemy';nameInput.handlers.input();
renderHostileTest();assert.equal(hostileNodes.find(n=>n.dataset.key==='hostile-name').value,'ManualEnemy','Polling preserves unfinished edits');
hostileNodes.find(n=>n.tag==='form').handlers.submit({preventDefault(){}});
assert.deepEqual(hostileRequests.at(-1).request,{op:'hostile-add',server:'test.invalid',name:'ManualEnemy',uuid:''});
const hostileToggle=hostileNodes.find(n=>n.dataset.key==='hostile-policy');hostileToggle.checked=true;hostileToggle.handlers.change();
assert.deepEqual(hostileRequests.at(-1).request,{op:'hostile-policy',enabled:true});assert.match(hostileRequests.at(-1).confirmation,/Enable immediate logout/);
hostileNodes.find(n=>n.tag==='button'&&n.text==='Remove').handlers.click();
assert.deepEqual(hostileRequests.at(-1).request,{op:'hostile-remove',id:'entry-id'});assert.match(hostileRequests.at(-1).confirmation,/Forgive Enemy/);
hostileWritable=false;renderHostileTest();assert.ok(hostileNodes.filter(n=>n.tag==='input'||n.tag==='button').every(n=>n.disabled),'Read-only sessions cannot edit host policy');
hostileState.knownHostiles.players=[];renderHostileTest();assert.ok(hostileNodes.some(n=>n.text.includes('No known hostiles')));
delete hostileState.knownHostiles;renderHostileTest();assert.ok(hostileNodes.some(n=>n.text.includes('Host update required')));
console.log('WebUI hostile-list checks passed: policy, add/remove, confirmation, edit persistence, support warnings and read-only/old-host states.');
hostileWritable=true;hostileState.knownHostiles={enabled:true,safeReconnect:true,reconnectSeconds:300,players:[]};renderHostileTest();
const delayInput=hostileNodes.find(n=>n.dataset.key==='safety-seconds');delayInput.value='420';delayInput.handlers.input();
const retryInput=hostileNodes.find(n=>n.dataset.key==='safety-retry');retryInput.checked=false;retryInput.handlers.change();
assert.equal(hostileContext.safetyDraft.reconnectSeconds,420);assert.equal(hostileContext.safetyDraft.safeReconnect,false);
renderHostileTest();assert.equal(hostileNodes.find(n=>n.dataset.key==='safety-seconds').value,420);
await hostileNodes.find(n=>n.tag==='button'&&n.text==='Save reconnect policy').handlers.click();
assert.deepEqual(hostileRequests.at(-1).request,{op:'hostile-policy',enabled:true,safeReconnect:false,reconnectSeconds:420});
hostileContext.incidentView={before:'',kind:'',search:'',data:{retained:1,capacity:2048,items:[{id:'one',kind:'sighting',workerName:'Worker',crew:'Crew',receivedAt:1000,evidence:{player:{name:'<script>not html</script>',x:1,y:116,z:3},classification:'neutral'}}]},error:''};
hostileState.incidentSummary={retained:1,capacity:2048};hostileContext.number=String;hostileContext.jsonDetails=(title,data)=>hostileEl('details','',title+JSON.stringify(data));
runInNewContext(hostilesSource+'\nrenderIncidents();',hostileContext);
assert.ok(hostileNodes.some(n=>n.tag==='h3'&&n.text.includes('<script>not html</script>')),'Timeline evidence renders through text nodes');
console.log('WebUI incident/reconnect checks passed: separate preserved settings, policy payload and text-only timeline evidence.');
const notificationsSource=source.slice(source.indexOf('function renderNotifications('),source.indexOf('function renderHostiles('));
const discordCalls=[];hostileContext.snapshot.discordNotifications={enabled:false,configured:false,ownerId:'123456789012345678',pending:0,delivered:0,dropped:0,error:''};
Object.assign(hostileContext,{discordDraft:null,busy:false,api:async(path,body)=>{discordCalls.push({path,body:structuredClone(body)});},notice(){},refresh:async()=>{},confirm:()=>true});
const renderDiscordTest=()=>{hostileNodes.length=0;runInNewContext(notificationsSource+'\nrenderNotifications();',hostileContext);};
renderDiscordTest();
const discordToken=hostileNodes.find(n=>n.dataset.key==='discord-botToken');assert.equal(discordToken.type,'password');discordToken.value='fake_bot_token';discordToken.handlers.input();
hostileNodes.find(n=>n.dataset.key==='discord-enabled').checked=true;hostileNodes.find(n=>n.dataset.key==='discord-enabled').handlers.change();
renderDiscordTest();assert.equal(hostileNodes.find(n=>n.dataset.key==='discord-botToken').value,'fake_bot_token','Polling preserves the unsaved secret only in memory');
await hostileNodes.find(n=>n.tag==='form').handlers.submit({preventDefault(){}});
assert.deepEqual(discordCalls.at(-1),{path:'control',body:{op:'discord-settings',enabled:true,ownerId:'123456789012345678',botToken:'fake_bot_token'}});assert.equal(hostileContext.discordDraft,null,'Successful save clears the in-memory token');
renderDiscordTest();hostileContext.snapshot.discordNotifications.enabled=true;hostileContext.snapshot.discordNotifications.configured=true;renderDiscordTest();
hostileNodes.find(n=>n.tag==='button'&&n.text==='Send test DM').handlers.click();assert.deepEqual(hostileRequests.at(-1).request,{op:'discord-test'});
hostileWritable=false;renderDiscordTest();assert.ok(hostileNodes.filter(n=>n.tag==='input'||n.tag==='button').every(n=>n.disabled));hostileWritable=true;
console.log('WebUI Discord checks passed: password field, polling edits, save payload, token clearing, test DM and read-only controls.');
const stashPanel = source.slice(source.indexOf('function fillStashPanel('), source.indexOf('function refreshStashPanels('));
const scanNode = {children: [], replaceChildren(){this.children=[];}, append(...nodes){this.children.push(...nodes);}};
runInNewContext(stashPanel + `
fillStashPanel(panel,{status:'Running',detail:'No safe walking route',stashScan:{name:'Depot',phase:'Approaching',reason:'No supported stance',observed:2,unscanned:1,missingChunks:1,discovery:100,volume:200,target:'1,116,0',movementTarget:'',receivedAt:Date.now()-6000,lastProgressAt:Date.now()-7000,lastAction:'Found container',attempts:1},stashEvents:[{receivedAt:Date.now(),phase:'Approaching',reason:'No supported stance',target:'1,116,0',runtimeDetail:'No safe walking route'}]});
assert.ok(panel.children.some(n=>n.cls==='error' && n.text.includes('Telemetry')));
assert.ok(panel.children.some(n=>n.text?.includes('1 unscanned')));
assert.ok(panel.children.some(n=>n.text?.includes('No safe walking route')));
`, {el,number:String,panel:scanNode,badge:(text)=>el('span','',text),tone:()=>'',assert,Date});
const timing = source.slice(source.indexOf('const currentRates ='), source.indexOf('function crewCard('));
runInNewContext(timing + `
sampleCurrentRate('crew','job',50,1000);
assert.equal(currentRates.get('crew').rate,null);
sampleCurrentRate('crew','job',51,6000);
assert.equal(currentRates.get('crew').rate,0.2);
assert.equal(predictionLine({nextBlocks:300,blocksPerSecond:0.2},'crew').children.map(n=>n.text).join(''),'0.2 blocks/sec current · estimated 0.2 blocks/sec over next ~25 min');
assert.equal(forecastDuration(300,6),'~50 sec');
assert.equal(forecastDuration(300,0),'unknown duration');
assert.equal(forecastDuration(7200,1),'~2.0 hr');
assert.equal(forecastDuration(172800,1),'~2.0 days');
for(const [rate,band] of [[0.2,'slow'],[1,'slow'],[2,'steady'],[4,'fast'],[5,'rapid'],[6,'exceptional'],[7,'exceptional'],[null,'unknown']])
  assert.equal(speedSpan(rate,'test').cls,'speed speed-'+band);
sampleCurrentRate('crew','new-job',0,7000);
assert.equal(currentRates.get('crew').rate,null);
rememberForecast('crew','crew',{nextBlocks:300,blocksPerSecond:0.2},false);
rememberForecast('crew','crew',null,true);
assert.ok(predictionLine(null,'crew').children[2].text.includes('0.2 blocks/sec over next ~25 min (held during resupply)'));
rememberForecast('crew','crew',null,false);
assert.equal(predictionLine(null,'crew').children[2].text,'visible-road estimate unavailable');
rememberForecast('crew','crew',{nextBlocks:300,blocksPerSecond:0.2},false);
sampleCurrentRate('crew','another-job',0,8000);
assert.equal(currentRates.get('crew').forecasts.size,0);
`, { el, number: String, assert });
console.log('WebUI measured rate and chunk horizon checks passed.');

// Exercise the actual dispatch handler without a browser or a live host.
const fields=new Map(),node=id=>{if(!fields.has(id))fields.set(id,{value:'',textContent:'',hidden:true,open:false,disabled:false,close(){this.open=false;},addEventListener(_event,handler){this.handler=handler;}});return fields.get(id);};
for(const [id,value] of Object.entries({'job-kind':'preset','job-preset':'task-follow','job-name':'Follow','job-crew':'Default','job-server':'test.invalid','job-dimension':'minecraft:the_nether','job-priority':'0','job-args':'{}','follow-leader':'leader','follow-radius':'3'}))node(id).value=value;
const calls=[],followers=[{id:'follower',scope:'test.invalid\nminecraft:the_nether'}];
const dispatchContext={$:node,busy:false,pendingSubmission:null,pendingSubmissionKey:null,reviewedSubmission:null,scanStash:null,packaged:null,presetList:[{id:'task-follow',entry:'task-follow'}],selectedWorkers:()=>followers,writable:()=>true,crypto:{randomUUID:()=> 'test-job'},render(){},notice(){},async refresh(){},async resource(method,_path,body){if(method==='GET')return {crew:'Default',crewId:'public-crew',connected:true};calls.push(structuredClone(body));return {id:body.id};},async api(_path,request){calls.push(structuredClone(request));return {entry:'task-follow',profiles:{Current:{}}};}};
runInNewContext(source.slice(source.indexOf("$('job-form').addEventListener('submit'"),source.indexOf("$('demo').addEventListener")),dispatchContext);
const submit=()=>node('job-form').handler({preventDefault(){}});
await submit();
assert.deepEqual(calls.map(c=>c.op),['workflow-prepare'],'Preview must not submit work');
assert.ok(dispatchContext.reviewedSubmission.package);
const reviewed=JSON.stringify(dispatchContext.reviewedSubmission);
await submit();assert.equal(JSON.stringify(calls[1]),reviewed,'Dispatch must use the reviewed snapshot');
dispatchContext.reviewedSubmission=null;followers.push({id:'leader',scope:'test.invalid\nminecraft:the_nether'});
await submit();assert.ok(node('job-error').textContent.includes('Uncheck the subject'));assert.equal(calls.length,2);
console.log('WebUI preset checks passed: non-mutating preview, captured dispatch and passive leader exclusion.');

const routeSources=[{name:'Source A',scope:'test.invalid\nminecraft:the_nether',kitTypes:{alpha:{name:'Obsidian'}}},{name:'Source B',kitTypes:{beta:{name:'Crying'}}},{name:'Main',kitTypes:{}},{name:'Crying',kitTypes:{}}];
const routeValues=[{name:'Source A',kitTypeId:'alpha',targetStashName:'Main'},{name:'Source B',kitTypeId:'beta',targetStashName:'Crying'}];
const routeRows=routeValues.map(route=>({querySelectorAll:()=>[{value:route.name},{value:route.kitTypeId},{value:route.targetStashName}]}));
const routeContext={kitSources:()=>routeSources,$:()=>({children:routeRows}),el,assert};
runInNewContext(source.slice(source.indexOf('function routeChoices('),source.indexOf('function updateKitTypes(')),routeContext);
assert.equal(JSON.stringify(routeContext.readKitRoutes()),JSON.stringify(routeValues));
routeRows.push(routeRows[0]);assert.throws(()=>routeContext.readKitRoutes(),/duplicate/);routeRows.pop();
const missing=routeRows[1];routeRows[1]={querySelectorAll:()=>[{value:'Source B'},{value:'beta'},{value:'Missing'}]};assert.throws(()=>routeContext.readKitRoutes(),/mapped/);routeRows[1]=missing;
const choices={dataset:{},value:'kept',renders:0,children:[],replaceChildren(){this.children=[];this.renders++;},append(option){this.children.push(option);}};
routeContext.routeChoices(choices,[['kept','Kit']]);routeContext.routeChoices(choices,[['kept','Kit']]);assert.equal(choices.renders,1,'Polling must not rebuild unchanged route controls');
routeContext.routeChoices(choices,[['other','Other']]);assert.equal(choices.value,'kept');assert.ok(choices.children.some(option=>option.text==='Unavailable · kept'),'Unavailable selections stay visible for validation, never silently switch destinations');
followers.splice(1);dispatchContext.presetList=[{id:'task-stash-transfer',entry:'task-stash-transfer'}];dispatchContext.kitSources=()=>routeSources;dispatchContext.readKitRoutes=()=>structuredClone(routeValues);
for(const [id,value] of Object.entries({'job-preset':'task-stash-transfer','kit-mode':'RoundRobin','kit-count':'36','kit-destination':'Stash','kit-source':'Source A','kit-type':'alpha'}))node(id).value=value;
node('kit-deposit-carried').checked=true;node('kit-include-incomplete').checked=false;await submit();
assert.equal(JSON.stringify(dispatchContext.reviewedSubmission.args.routes),JSON.stringify(routeValues));assert.equal(dispatchContext.reviewedSubmission.args.count,36);assert.ok(dispatchContext.reviewedSubmission.args.depositCarried);assert.ok(!('name' in dispatchContext.reviewedSubmission.args),'Round Robin must not inherit the single-source fields');
const routeLabel=runInNewContext(source.slice(source.indexOf('function kitJobLabel('),source.indexOf('function kitUsage('))+'\nkitJobLabel;',{});
assert.ok(routeLabel({count:36,routes:routeValues}).includes('Source A → Main / Source B → Crying'));
console.log('WebUI Round Robin checks passed: alternating sources/destinations, preserved selections, guarded preview and route labels.');
const kitContext=runInNewContext(source.slice(source.indexOf('function kitSignature('),source.indexOf('function deliveryWorkers('))+'\n({kitBoxType,kitLocations});',{});
const kitTypes={a:{item:'minecraft:blue_shulker_box',name:'empty',items:{'minecraft:stone':64}},b:{item:'minecraft:blue_shulker_box',name:'empty',items:{'minecraft:obsidian':64}}};
assert.equal(kitContext.kitBoxType({...kitTypes.a,kitTypeId:'a'},kitTypes),'a');
assert.equal(kitContext.kitBoxType({...kitTypes.a,kitTypeId:'legacy'},kitTypes,{legacy:'a'}),'a');
assert.equal(kitContext.kitBoxType(kitTypes.a,kitTypes),'a','Legacy boxes use unambiguous contents, not the last same-name type');
assert.equal(kitContext.kitBoxType({...kitTypes.a,items:{'minecraft:stone':1,'minecraft:obsidian':1}},kitTypes),'','Ambiguous legacy contents cannot guess a column reservation');
const kitSource={id:'a',stash:{kitTypes}},kitDb={kitTypes,containers:{a:{x:0,y:1,z:2,status:'observed',shulkers:[{...kitTypes.a,kitTypeId:'a',quantity:2}]},b:{x:1,y:1,z:2,status:'observed',shulkers:[{...kitTypes.b,kitTypeId:'b',quantity:3}]}}};
assert.equal(kitContext.kitLocations(kitDb,kitSource).length,1);assert.equal(kitContext.kitLocations(kitDb,kitSource)[0].count,2,'Disjoint same-name variants never appear at one another’s chest locations');
console.log('WebUI kit variant checks passed: assigned IDs, aliases, legacy content evidence and correct chest locations.');

const receiptTask={id:'job',controlReceipts:{request:{command:'cancel',state:'delivery_queued',workers:{worker:{state:'delivery_queued'}}}}};
const receiptContent={children:[],append(node){this.children.push(node);}};
const receiptEl=(tag,cls='',text='')=>({tag,cls,text,children:[],append(...nodes){this.children.push(...nodes);},replaceChildren(...nodes){this.children=nodes;},querySelector(){return this.children.find(n=>n.tag==='details');}});
const receiptContext={id:'job',task:receiptTask,snapshot:{tasks:[receiptTask],history:[]},content:receiptContent,el:receiptEl,jsonDetails:(title,value)=>({...receiptEl('details','',title),value})};
runInNewContext(source.slice(source.indexOf('const receiptView='),source.indexOf('if(!terminal(task.status))content.append(guidedSettings(task));')),receiptContext);
const receiptPanel=receiptContent.children[0];assert.ok(receiptPanel.children.some(n=>n.text==='cancel · delivery queued'));
receiptPanel.querySelector().open=true;receiptTask.controlReceipts.request.state='completed';receiptPanel.updateStatus();
assert.ok(receiptPanel.children.some(n=>n.text==='cancel · completed'));assert.equal(receiptPanel.querySelector().open,true,'Polling preserves expanded receipt evidence');
assert.equal(receiptPanel.querySelector().value.workers.worker.state,'delivery_queued','Per-worker evidence is shown separately from the aggregate');
receiptTask.controlReceipts={};receiptPanel.updateStatus();assert.ok(receiptPanel.children.some(n=>n.text==='No correlated command receipt yet.'));
console.log('WebUI receipt checks passed: separate queue/worker outcomes, live updates, preserved expansion and older jobs.');
