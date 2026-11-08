const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
let checks=0;const check=(value,message)=>{assert.ok(value,message);checks++};
class Element {
  constructor(){this.hidden=false;this.style={};this.dataset={};this.children=[];this.listeners={};this.clientWidth=800;this.clientHeight=600;}
  append(...children){this.children.push(...children)} replaceChildren(){this.children=[]} setAttribute(k,v){this[k]=v} addEventListener(k,v){this.listeners[k]=v}
}
const nodes=new Map();const get=id=>{if(!nodes.has(id))nodes.set(id,new Element());return nodes.get(id)};get('network-map').hidden=true;
const point=(x,y)=>({x,y,add(b){return point(x+b.x,y+b.y)},divideBy(n){return point(x/n,y/n)},distanceTo(b){return Math.hypot(x-b.x,y-b.y)}});
let fakeMap,tiles,vectorCount=0,mapOptions,reduced=true,frameId=0,now=0;
const frames=new Map();
function frame(time){now=time;const callbacks=[...frames.values()];frames.clear();callbacks.forEach(fn=>fn(time));}
class MapStub {
 constructor(){this.zoom=1;this.center=[0,0];this.callbacks={};this.active=new Set();}
 on(k,fn){this.callbacks[k]=fn;return this} fire(k){this.callbacks[k]?.()}
 setView(c,z){if(z!==this.zoom)this.fire('zoomstart');const changed=z!==this.zoom;this.center=Array.isArray(c)?c:[c.lat,c.lng];this.zoom=z;if(changed)this.fire('zoomend');this.fire('moveend');return this}
 setZoom(z){return this.setView(this.center,z)} getZoom(){return this.zoom} getCenter(){return {lat:this.center[0],lng:this.center[1]}}
 setZoomAround(point,z){this.anchor=point;return this.setZoom(z)}
 getContainer(){return get('network-map')} getSize(){return point(800,600)} invalidateSize(){} hasLayer(layer){return this.active.has(layer)} addLayer(layer){this.active.add(layer)} removeLayer(layer){this.active.delete(layer)}
 fitBounds(values,options){this.setView(values[0],options.maxZoom===10?8:12)}
 project(c,z){return point(c[1]*2**z,-c[0]*2**z)} unproject(p,z){return [-p.y/2**z,p.x/2**z]}
 latLngToContainerPoint(c){return point((c[1]-this.center[1])*2**this.zoom+400,(this.center[0]-c[0])*2**this.zoom+300)}
 mouseEventToContainerPoint(e){return point(e.x,e.y)}
}
function layer(){return {callbacks:{},addTo(){vectorCount++;return this},on(k,f){this.callbacks[k]=f;return this},getElement(){return new Element()},bindTooltip(){},setLatLng(){return this},setContent(){return this},openOn(){return this}}}
const control=()=>({addTo(){this.onAdd?.();return this}});control.scale=()=>({addTo(){}});
const context={console,JSON,Number,Math,Map,Set,document:{getElementById:get,createElement:()=>new Element(),createTextNode:text=>text},matchMedia:()=>({get matches(){return reduced}}),setTimeout:()=>1,clearTimeout(){},
 performance:{now:()=>now},requestAnimationFrame:fn=>{frames.set(++frameId,fn);return frameId},cancelAnimationFrame:id=>frames.delete(id),
 L:{map:(id,options)=>{mapOptions=options;return fakeMap=new MapStub()},layerGroup:()=>({addTo(){return this},clearLayers(){vectorCount=0}}),control,
 tileLayer:()=>tiles={...layer(),isLoading:()=>true},DomEvent:{disableClickPropagation(){},disableScrollPropagation(){}},marker:layer,polyline:layer,divIcon:v=>v,point,popup:layer},
 state:{scope:'network',systemId:null,network:{systems:[{id:'A',center:[31,-102],truck_yard:[31.02,-102],disposal_site:{id:'DISP',lat:31.01,lon:-102},facilities:[{id:'FAC',lat:31.01,lon:-102.01,wells:[]}]}]},events:[]},
 ongoing:events=>events.filter(e=>e.status==='ongoing'),selectAsset(){},
 systemById(){return context.state.network.systems.find(s=>s.id===context.state.systemId)},facilityById(){return {facility:context.state.network.systems[0].facilities[0]}},viewKey(){return `${context.state.scope}:${context.state.systemId??''}:`},
 navigate(scope,id,facility){context.state.scope=scope;context.state.systemId=id;context.state.facilityId=facility;if(scope==='facility')run('GeoMap.hide()');else run('GeoMap.render()')}
};
vm.createContext(context);vm.runInContext(fs.readFileSync('websiteFiles/js/geography.js','utf8'),context);const run=code=>vm.runInContext(code,context);
check(run('GeoMap.coordinate({lat:0,lon:0})[0]===0'),'zero coordinates retained');
check(run('GeoMap.coordinate({lat:91,lon:0})===null'),'invalid latitude rejected');
check(run('GeoMap.coordinate({lat:"31",lon:0})===null'),'nonnumeric location rejected');
run('GeoMap.render()');check(vectorCount>0,'network markers and connections rendered');
check(context.state.scope==='network','programmatic fit does not drill down');
check(mapOptions.zoomAnimation===false && mapOptions.fadeAnimation===false,'reduced motion disables map animation');
context.navigate('facility','A','FAC');run('GeoMap.leaveFacility()');check(fakeMap.center[0]===31.01,'direct facility entry returns near that facility');context.navigate('network',null);
fakeMap.setView([31,-102],10);run('GeoMap.zoom(1)');check(context.state.scope==='system','user zoom 11 enters nearby system');
fakeMap.setView([31.01,-102.01],14);check(context.state.scope==='system','programmatic camera does not enter facility');
run('GeoMap.zoom(1)');check(context.state.scope==='facility','user zoom 15 enters nearby facility');
run('GeoMap.leaveFacility()');check(context.state.scope==='system' && fakeMap.zoom===14,'reverse navigation restores system at zoom 14');
for(let i=0;i<5;i++)run('GeoMap.zoom(-1)');check(context.state.scope==='network','zooming out to 9 returns to network');
fakeMap.setView([0,0],10);run('GeoMap.zoom(1)');check(context.state.scope==='network','zoom away from markers does not navigate');
fakeMap.setView([31,-102],10);get('network-map').listeners.keydown({key:'+'});fakeMap.setZoom(11);check(context.state.scope==='system','keyboard zoom enters system');
fakeMap.setView([31.01,-102.01],14);
get('network-map').listeners.pointerdown({pointerType:'touch',pointerId:1,x:300,y:200});
get('network-map').listeners.pointerdown({pointerType:'touch',pointerId:2,x:500,y:400});
get('network-map').listeners.pointermove({pointerId:1,x:300,y:200});fakeMap.setZoom(15);
check(context.state.scope==='facility','pinch midpoint controls facility transition');run('GeoMap.leaveFacility()');
const before=vectorCount;for(let i=0;i<3;i++)tiles.callbacks.tileerror();check(vectorCount===before && get('map-status').textContent.includes('unavailable'),'tile errors preserve network overlays');
context.state.scope='network';context.state.systemId=null;
context.state.network={systems:[{id:'MISSING',facilities:[{id:'NO_COORD',wells:[]}]}]};run('GeoMap.reset(); GeoMap.render()');
check(get('location-entries').children.some(n=>n.textContent.includes('NO_COORD')&&n.textContent.includes('Location unavailable')),'missing coordinates remain navigable');
context.state.network={systems:[{id:'ONE',center:[31,-102],facilities:[{id:'F1',lat:31,lon:-102,wells:[]},{id:'F2',lat:31,lon:-102,wells:[]}]}]};run('GeoMap.reset(); GeoMap.render()');
check(get('location-entries').children.length===3,'overlapping locations all available in list');
get('network-map').clientWidth=0;run('GeoMap.fit()');get('network-map').clientWidth=800;run('GeoMap.resize()');check(fakeMap.zoom===8,'hidden mobile map fits on becoming visible');
check(mapOptions.scrollWheelZoom===false && mapOptions.zoomSnap===0,'native stepped wheel zoom and snapping are disabled');
reduced=false;fakeMap.setView([0,0],8);
const wheel={deltaY:-15,deltaMode:0,x:250,y:200,target:{closest:()=>null},preventDefault(){this.prevented=true}};
get('network-map').listeners.wheel(wheel);frame(16);
check(fakeMap.zoom>8 && fakeMap.zoom<8.125,'wheel zoom interpolates fractional levels on animation frames');
get('network-map').listeners.wheel(wheel);
check(frames.size===1,'repeated wheel input retargets one animation loop');
frame(32);frame(64);frame(128);
check(fakeMap.zoom===8.25,'wheel target accumulates at half the previous sensitivity');
check(fakeMap.anchor.x===250 && fakeMap.anchor.y===200 && wheel.prevented,'continuous zoom keeps wheel focus and prevents native scrolling');
get('network-map').listeners.wheel(wheel);run('GeoMap.fit()');check(frames.size===0,'programmatic fit cancels pending continuous zoom');
context.state.network={systems:[{id:'CONTINUE',center:[31,-102],facilities:[]}]};run('GeoMap.reset();GeoMap.render()');fakeMap.setView([31,-102],10.5);
get('network-map').listeners.wheel({...wheel,deltaY:-180,x:400,y:300});frame(144);
check(context.state.scope==='system' && fakeMap.zoom>11 && fakeMap.zoom<12,'zoom drill-down preserves fractional camera instead of refitting');
frame(176);frame(260);check(fakeMap.zoom===12,'wheel destination continues across network-to-system navigation');
console.log(`PASS: ${checks} geographic behavior assertions`);
