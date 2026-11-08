const fs = require('node:fs'), vm = require('node:vm'), assert = require('node:assert/strict');
let checks = 0, frameId = 0, reduced = false, applied = 0, returned = 0;
const check = (value,label) => { assert.ok(value,label); checks++; };
const pending = new Map(), value = {x:0,y:0,scale:1};
const context = {state:{bounds:{},scope:'facility',facilityFits:new Map([['facility',1]])},
  transform:()=>value, viewKey:()=>context.state.scope,
  applyTransform:()=>applied++, performance:{now:()=>0}, matchMedia:()=>({matches:reduced}),
  requestAnimationFrame:fn=>{pending.set(++frameId,fn);return frameId},cancelAnimationFrame:id=>pending.delete(id),
  GeoMap:{leaveFacility:()=>{returned++;context.state.scope='system'}}};
const code = fs.readFileSync('websiteFiles/js/app.js','utf8'); vm.createContext(context);
vm.runInContext(code.slice(code.indexOf('let zoomFrame ='),code.indexOf('function zoomCenter(')),context);
const run = code => vm.runInContext(code,context);
function frame(time){const callbacks=[...pending.values()];pending.clear();callbacks.forEach(fn=>fn(time));}
run('zoomAt(1.2,100,100,true)');check(value.scale===1,'animated zoom does not jump immediately');
frame(45);check(value.scale>1 && value.scale<1.2,'animation interpolates scale');
frame(90);check(value.scale===1.2 && pending.size===0,'animation reaches exact target within 90ms');
check(Math.abs((100-value.x)/value.scale-100)<1e-8,'zoom keeps the focus point anchored');
run('zoomAt(1.2,100,100,true);zoomAt(1.2,100,100,true)');frame(160);
check(Math.abs(value.scale-1.728)<1e-8,'rapid wheel inputs accumulate without losing their target');
run('zoomAt(1.1,100,100,true);zoomAt(1.1,100,100,true)');check(pending.size===1,'wheel input retargets one running animation instead of restarting it');frame(160);
reduced=true;run('zoomAt(1.1,100,100,true)');check(pending.size===0,'reduced motion applies zoom immediately');
reduced=false;run('zoomAt(.2,100,100,true)');check(returned===1 && context.state.scope==='system','reverse zoom still exits the facility');
context.state.scope='facility';value.scale=1;run('zoomAt(1.2,100,100,true)');context.state.scope='system';const before=applied;frame(160);
check(applied===before && pending.size===0,'navigation cancels a pending schematic animation');
console.log(`PASS: ${checks} zoom animation assertions`);
