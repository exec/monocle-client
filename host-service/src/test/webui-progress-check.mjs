// Run: node host-service/src/test/webui-progress-check.mjs
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
const source = readFileSync(new URL('../main/resources/webui/app.js', import.meta.url), 'utf8');
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
