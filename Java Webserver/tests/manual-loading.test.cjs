const fs = require('node:fs'), vm = require('node:vm'), assert = require('node:assert/strict');
const code = fs.readFileSync('websiteFiles/js/app.js', 'utf8');
let checks=0; const check=(value,label)=>{assert.ok(value,label);checks++};
const nodes = new Map();
const get=id=>{if(!nodes.has(id))nodes.set(id,{value:'',textContent:'',disabled:false,focus(){}});return nodes.get(id)};
let selected;
const context={URL,decodeURIComponent,$:get,state:{choices:[]},ACTIVE_API:{validateFolder:async()=>null},
 folderUrl:path=>'https://github.com/nihaal419/HackUTD-ZeroDay/tree/main/'+path,
 selectDataset:choice=>selected=choice};
vm.createContext(context);
vm.runInContext(code.slice(code.indexOf('async function manualDataset('),code.indexOf('/* RENDERING')),context);
const event={preventDefault(){},currentTarget:{querySelector:()=>({disabled:false})}};
async function submit(input){selected=null;get('manual-path').value=input;await context.manualDataset(event);return selected}
(async()=>{
 let choice=await submit('https://github.com/team/custom-data/tree/main/judging/new-recording');
 check(choice?.url==='https://github.com/team/custom-data/tree/main/judging/new-recording','full pasted URL goes to existing backend unchanged');
 check(choice.path==='judging/new-recording','folder label extracted from URL');
 choice=await submit('history/snapshot_2026-10-08T1200');
 check(choice?.url.endsWith('/tree/main/history/snapshot_2026-10-08T1200'),'relative challenge paths still supported');
 check(!(await submit('https://example.com/team/data/tree/main/recording')),'non-GitHub URLs rejected');
 check(!(await submit('http://github.com/team/data/tree/main/recording')),'insecure URLs rejected');
 check(!(await submit('../recording')),'relative parent traversal rejected');
 check(!(await submit('https://github.com/team/data/blob/main/network.json')),'individual file links rejected');
 check(!code.includes("this.request('/api/datasets") && !code.includes('setInterval(') && !code.includes("addEventListener('visibilitychange'"),'no repository discovery calls or automatic polling');
 let loaded=0;context.DEMO_MODE=false;context.state.selectedFolder=null;context.selectDataset=()=>loaded++;
 vm.runInContext(code.slice(code.indexOf('let discovering ='),code.indexOf('function renderDatasets(')),context);
 await context.discover();check(loaded===0 && get('manual').open,'refresh without a recording opens manual form');
 context.state.selectedFolder={url:'https://github.com/team/data/tree/main/recording'};await context.discover();
 check(loaded===1,'refresh reanalyzes only the explicitly selected folder');
 console.log(`PASS: ${checks} manual loading assertions`);
})().catch(error=>{console.error(error);process.exitCode=1});
