'use strict';

/* Geographic camera state is separate from equipment schematic transforms. */
const GeoMap = (() => {
  let map, layers, tiles, key = null, network = null, userZoom = false, focus = null;
  let needsFit = false;
  let previousZoom = 0, tileErrors = 0, tileTimer, backdrop = true;
  const cameras = new Map(), reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const touches = new Map();
  const tileUrl = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';

  function coordinate(value) {
    const lat = Array.isArray(value) ? value[0] : value?.lat;
    const lon = Array.isArray(value) ? value[1] : value?.lon;
    return Number.isFinite(lat) && Number.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180 ? [lat, lon] : null;
  }
  function remember() {
    if (map && key && !document.getElementById('network-map').hidden)
      cameras.set(key, { center: map.getCenter(), zoom: map.getZoom() });
  }
  function status(text) { const node = document.getElementById('map-status'); node.textContent = text; node.hidden = !text || state.scope === 'facility'; }
  function fallback() {
    if (tiles && map.hasLayer(tiles)) map.removeLayer(tiles);
    clearTimeout(tileTimer);
    status('Street map unavailable · positions and connections remain usable. Toggle Street map to retry.');
  }
  function addTiles() {
    tileErrors = 0;
    map.addLayer(tiles);
    status('Loading street map…');
    clearTimeout(tileTimer);
    tileTimer = setTimeout(() => { if (!tiles.isLoading()) return; fallback(); }, 8000);
  }
  function ensureMap() {
    if (map) return true;
    if (typeof L === 'undefined') { status('Map library unavailable. Use the locations list to explore equipment.'); return false; }
    map = L.map('network-map', { zoomControl: false, zoomSnap: .25, zoomDelta: 1, maxZoom: 19,
      minZoom: 1, zoomAnimation: !reduced.matches, fadeAnimation: !reduced.matches, attributionControl: true });
    map.setView([0,0],1,{animate:false});
    layers = L.layerGroup().addTo(map);
    L.control.scale({ imperial: true, metric: true, position: 'bottomleft' }).addTo(map);
    tiles = L.tileLayer(tileUrl, { maxZoom: 19, keepBuffer: 0,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors' });
    tiles.on('tileerror', () => { if (++tileErrors >= 3) fallback(); });
    tiles.on('load', () => { clearTimeout(tileTimer); if (!tileErrors) status(''); });
    const control = L.control({ position: 'topleft' });
    control.onAdd = () => {
      const label = document.createElement('label'); label.className = 'map-backdrop-control';
      const input = document.createElement('input'); input.type = 'checkbox'; input.checked = true;
      input.addEventListener('change', () => {
        backdrop = input.checked;
        if (backdrop) addTiles(); else { clearTimeout(tileTimer); map.removeLayer(tiles); status('Street map off · geographic positions retained'); }
      });
      label.append(input, document.createTextNode('Street map')); L.DomEvent.disableClickPropagation(label); L.DomEvent.disableScrollPropagation(label);
      return label;
    };
    control.addTo(map);
    const container = map.getContainer();
    container.addEventListener('wheel', event => { userZoom = true; focus = map.mouseEventToContainerPoint(event); }, { passive: true });
    container.addEventListener('dblclick', event => { userZoom = true; focus = map.mouseEventToContainerPoint(event); });
    container.addEventListener('keydown', event => { if (['+', '-', '=', '_'].includes(event.key)) { userZoom = true; focus = map.getSize().divideBy(2); } });
    container.addEventListener('pointerdown', event => {
      if (event.pointerType !== 'touch') return;
      touches.set(event.pointerId, map.mouseEventToContainerPoint(event));
      userZoom = true; focus = map.mouseEventToContainerPoint(event);
    });
    container.addEventListener('pointermove', event => {
      if (!touches.has(event.pointerId)) return;
      touches.set(event.pointerId, map.mouseEventToContainerPoint(event));
      if (touches.size > 1) { const points = [...touches.values()]; focus = points.reduce((a,b) => a.add(b), L.point(0,0)).divideBy(points.length); userZoom = true; }
    });
    for (const type of ['pointerup', 'pointercancel']) container.addEventListener(type, event => touches.delete(event.pointerId));
    map.on('zoomstart', () => { previousZoom = map.getZoom(); });
    map.on('zoomend', () => {
      document.getElementById('zoom-value').textContent = `Z ${map.getZoom().toFixed(1)}`;
      remember();
      const user = userZoom; userZoom = false;
      if (user && state.scope !== 'facility' && map.getZoom() !== previousZoom) transition(map.getZoom() > previousZoom);
    });
    map.on('moveend', remember);
    return true;
  }
  function camera(value) { userZoom = false; map.setView(value.center, value.zoom, { animate: false }); }
  function transition(inward) {
    if (!inward && state.scope === 'system' && map.getZoom() <= 9) { navigate('network'); return; }
    if (!inward) return;
    const candidates = state.scope === 'network' && map.getZoom() >= 11 ? state.network.systems.map(system => ({ id: system.id, point: coordinate(system.center) }))
      : state.scope === 'system' && map.getZoom() >= 15 ? (systemById()?.facilities ?? []).map(facility => ({ id: facility.id, point: coordinate(facility) })) : [];
    const target = focus ?? map.getSize().divideBy(2);
    const nearest = candidates.filter(item => item.point).map(item => ({ ...item, distance: map.latLngToContainerPoint(item.point).distanceTo(target) }))
      .filter(item => item.distance <= 80).sort((a,b) => a.distance - b.distance || a.id.localeCompare(b.id))[0];
    if (nearest) {
      if (state.scope === 'network') navigate('system', nearest.id);
      else navigate('facility', state.systemId, nearest.id);
    }
  }
  function marker(point, title, kind, count, action) {
    if (!point) return;
    const content = document.createElement('span'); content.className = `geo-marker ${kind}${count ? ' alert' : ''}`;
    content.textContent = kind === 'system' ? title : kind === 'disposal' ? '◆' : kind === 'yard' ? 'T' : '●';
    if (count) { const badge = document.createElement('b'); badge.textContent = count; content.append(badge); }
    const node = L.marker(point, { icon: L.divIcon({ html: content, className: 'geo-marker-shell', iconSize: kind === 'system' ? [94,36] : [28,28], iconAnchor: kind === 'system' ? [47,18] : [14,14] }),
      title: `${title}${count ? ` · ${count} ongoing alerts` : ''}`, alt: title, keyboard: true, riseOnHover: true, zIndexOffset: kind === 'system' ? 1000 : 0 }).addTo(layers);
    const label = document.createElement('span'); label.textContent = title;
    node.bindTooltip(label, { direction: 'top' }); node.on('click', action);
    node.getElement().setAttribute('aria-label', `${title}${count ? ` · ${count} ongoing alerts` : ''}`);
  }
  function connection(from, to, count) {
    if (!from || !to) return;
    const color = count ? '#b42318' : '#526b7c';
    L.polyline([from, to], { color, weight: count ? 3 : 2, opacity: .8, dashArray: '6 5', interactive: false }).addTo(layers);
    if (from[0] === to[0] && from[1] === to[1]) return;
    const a = map.project(from, 10), b = map.project(to, 10), middle = map.unproject(a.add(b).divideBy(2), 10);
    const arrow = document.createElement('span'); arrow.className = 'connection-arrow'; arrow.textContent = '➤';
    arrow.style.color = color; arrow.style.transform = `rotate(${Math.atan2(b.y-a.y,b.x-a.x)*180/Math.PI}deg)`;
    L.marker(middle, { interactive: false, keyboard: false, icon: L.divIcon({ html: arrow, className: 'connection-arrow-shell', iconSize:[18,18], iconAnchor:[9,9] }) }).addTo(layers);
  }
  function entries() {
    const list = document.getElementById('location-entries'); list.replaceChildren();
    const systems = state.scope === 'network' ? state.network.systems : [systemById()].filter(Boolean);
    for (const system of systems) {
      const items = [{ title: system.id, point: coordinate(system.center), action: () => navigate('system',system.id) },
        ...system.facilities.map(facility => ({ title: facility.id, point: coordinate(facility), action: () => navigate('facility',system.id,facility.id) }))];
      if (system.disposal_site) items.push({ title: system.disposal_site.id, point: coordinate(system.disposal_site), action: () => { navigate('system',system.id); selectAsset(system.disposal_site.id); } });
      if (system.truck_yard) items.push({ title: `${system.id} truck yard`, point: coordinate(system.truck_yard), action: () => navigate('system',system.id) });
      for (const item of items) {
        const node = document.createElement('button'); node.type = 'button'; node.textContent = `${item.title} · ${item.point ? item.point.map(value => value.toFixed(4)).join(', ') : 'Location unavailable'}`;
        node.addEventListener('click', item.action); list.append(node);
      }
    }
  }
  function points() {
    const systems = state.scope === 'network' ? state.network.systems : [systemById()].filter(Boolean);
    return systems.flatMap(system => [coordinate(system.center), coordinate(system.disposal_site), coordinate(system.truck_yard), ...system.facilities.map(coordinate)]).filter(Boolean);
  }
  function fit() {
    if (!map || state.scope === 'facility') return;
    userZoom = false;
    if (!map.getContainer().clientWidth || !map.getContainer().clientHeight) { needsFit = true; return; }
    needsFit = false;
    map.invalidateSize({ pan:false, animate:false });
    const values = points();
    if (values.length) map.fitBounds(values, { padding: [45,55], maxZoom: state.scope === 'network' ? 10 : 14, animate: false });
    else map.setView([0,0],1,{animate:false});
    remember();
  }
  function render() {
    const wasHidden = document.getElementById('network-map').hidden;
    document.getElementById('network-map').hidden = false;
    document.getElementById('diagram').style.display = 'none';
    document.getElementById('map-status').hidden = !document.getElementById('map-status').textContent;
    document.getElementById('location-list').hidden = false;
    entries();
    if (!ensureMap()) return;
    const nextKey = viewKey();
    if (key !== nextKey && !wasHidden) remember();
    const changed = key !== nextKey || network !== state.network || wasHidden;
    key = nextKey; network = state.network;
    userZoom = false; map.invalidateSize({ pan:false, animate:false });
    if (changed) {
      const saved = cameras.get(key);
      if (saved) camera(saved); else fit();
      if (backdrop && !map.hasLayer(tiles)) addTiles();
    }
    layers.clearLayers();
    const systems = state.scope === 'network' ? state.network.systems : [systemById()].filter(Boolean);
    for (const system of systems) {
      const count = ongoing(state.events.filter(event => event.system_id === system.id)).length;
      const disposal = coordinate(system.disposal_site);
      for (const facility of system.facilities) {
        const facilityCount = ongoing(state.events.filter(event => event.facility_id === facility.id)).length;
        connection(coordinate(facility),disposal,facilityCount);
        marker(coordinate(facility),facility.id,'facility',facilityCount,() => navigate('facility',system.id,facility.id));
      }
      if (system.disposal_site) marker(disposal,system.disposal_site.id,'disposal',0,() => { if (state.scope === 'network') navigate('system',system.id); selectAsset(system.disposal_site.id); });
      if (state.scope === 'network') marker(coordinate(system.center),system.id,'system',count,() => navigate('system',system.id));
      else marker(coordinate(system.truck_yard),`${system.id} truck yard`,'yard',0,() => {
        const text = document.createElement('span'); text.textContent = `${system.id} truck yard · coordinates supplied by dataset`;
        L.popup().setLatLng(coordinate(system.truck_yard)).setContent(text).openOn(map);
      });
    }
    if (!points().length) status('No valid coordinates supplied. Explore the locations list below.');
    document.getElementById('zoom-value').textContent = `Z ${map.getZoom().toFixed(1)}`;
  }
  function hide() {
    remember();
    document.getElementById('network-map').hidden = true;
    document.getElementById('diagram').style.display = '';
    document.getElementById('map-status').hidden = true;
    document.getElementById('location-list').hidden = true;
  }
  function reset() { cameras.clear(); key = null; network = null; }
  function zoom(delta) { if (!map) return; userZoom = true; focus = map.getSize().divideBy(2); map.setZoom(map.getZoom()+delta,{animate: !reduced.matches}); }
  function resize() { if (map && state.scope !== 'facility') { userZoom = false; map.invalidateSize({ pan:true, animate:false }); if (needsFit) fit(); } }
  function leaveFacility() {
    const system = systemById(), saved = cameras.get(`system:${state.systemId}:`);
    const center = saved?.center ?? coordinate(facilityById()?.facility) ?? coordinate(system?.center);
    if (center) cameras.set(`system:${state.systemId}:`, { center, zoom:14 });
    navigate('system',state.systemId);
  }
  return { coordinate, render, hide, remember, reset, fit, zoom, resize, leaveFacility };
})();
