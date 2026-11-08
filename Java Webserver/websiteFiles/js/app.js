'use strict';
/* CONFIGURATION — dataset metadata is separate from analysis results. */
const DATA_SOURCE = { owner: 'nihaal419', repo: 'HackUTD-ZeroDay', ref: 'main' };
const PREFERRED_DATASET = 'history/snapshot_2026-10-08T1200';
const DEMO_MODE = new URLSearchParams(location.search).get('mode') === 'demo';
const $ = id => document.getElementById(id);
const finite = value => typeof value === 'number' && Number.isFinite(value) ? value : null;
const idOf = value => value == null ? null : String(value);
const encodedPath = path => path.split('/').map(encodeURIComponent).join('/');
const folderUrl = path => `https://github.com/${DATA_SOURCE.owner}/${DATA_SOURCE.repo}/tree/${encodeURIComponent(DATA_SOURCE.ref)}/${encodedPath(path)}`;

/* LIVE API ADAPTER — provisional wire contracts live only here. */
const API = {
  async request(path, options = {}) {
    const response = await fetch(path, { ...options, headers: { Accept: 'application/json', ...options.headers } });

    let data;
    try { data = await response.json(); } catch { throw new Error('The server returned an unexpected response. Paste a GitHub dataset folder link to analyze it.'); }
    if (!response.ok) throw new Error(data['response-text'] ?? `Request failed (HTTP ${response.status}).`);
    this.checkMetadata(data);
    return data;
  },
  checkMetadata(data) {
    if (data && typeof data === 'object' && !Array.isArray(data)) {
      if (data.success === false || (typeof data.response === 'number' && (data.response < 200 || data.response >= 300))) {
        throw new Error(typeof data['response-text'] === 'string' ? data['response-text'] : 'Backend reported an unsuccessful operation.');
      }
    }
  },
  eventResult(data) {
    const events = Array.isArray(data) ? data : data && Array.isArray(data.events) ? data.events : null;
    if (!events) throw new Error('Expected an event array or {events: [...]}. Align the event response contract.');
    const normalized = events.map(normalizeEvent);
    const ids = new Set();
    for (const event of normalized) {
      if (ids.has(event.event_id)) throw new Error(`Duplicate event ID: ${event.event_id}. Backend IDs must be unique.`);
      ids.add(event.event_id);
    }
    return {
      events: normalized,
      asOf: timestamp(data?.as_of_unix, data?.as_of_ts),
      datasetId: !Array.isArray(data) ? data?.dataset_id ?? null : null,
      revision: data?.source_revision ?? null
    };
  },
  async analyzeDataset(url) {
    return this.eventResult(await this.request('/api/getevents', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ 'github-repo': url })
    }));
  },
  async fetchNetwork(context) {
    return normalizeNetwork(await this.request(`/api/getnetwork?dataset_id=${encodeURIComponent(context.datasetId)}`));
  },
  async sortEvents(mode, context) {
    return this.eventResult(await this.request(`/api/resortevents?pass-params=${encodeURIComponent(mode)}&dataset_id=${encodeURIComponent(context.datasetId)}`));
  },
  async exportEvents(context) {
    return this.request(`/api/exportevents?dataset_id=${encodeURIComponent(context.datasetId)}`);
  },
  async validateFolder() { return null; },
  async discoverDatasets() {
    return []; // Live recordings are supplied explicitly through the folder-link form.
  }
};

/* DEMO ADAPTER — deterministic local data; it never calls backend or GitHub APIs. */
const DEMO_EVENTS = [
  {
    event_id: 'demo-pressure-01', system_id: 'north-hub', facility_id: 'north-pad',
    asset_id: 'north-pump-a', category: 'pump_failure', status: 'ongoing',
    onset_unix: 1791532800, confidence: 0.94, level_pct: 82, est_cost: 18400,
    hours_to_limit: { p10: 5, p50: 9, p90: 16 },
    evidence: ['Discharge pressure is 18% above the operating band.', 'Telemetry trend accelerated over the last 40 minutes.'],
    recommended_actions: [{ type: 'field_inspection', asset_id: 'north-pump-a' }, { type: 'none' }]
  },
  {
    event_id: 'demo-tank-02', system_id: 'north-hub', facility_id: 'north-pad',
    asset_id: 'north-tank-1', category: 'level', status: 'ongoing',
    onset_unix: 1791529200, confidence: 0.87, level_pct: 91, est_cost: 7200,
    hours_to_limit: { p10: 12, p50: 22, p90: 36 },
    evidence: ['Tank level is approaching the configured shutdown threshold.'],
    recommended_actions: [{ type: 'none' }]
  },
  {
    event_id: 'demo-flow-03', system_id: 'south-hub', facility_id: 'south-pad',
    asset_id: 'south-pipeline-1', category: 'pipeline_leak', status: 'resolved',
    onset_unix: 1791457200, end_unix: 1791471600, confidence: 0.91, est_cost: 3150,
    evidence: ['Flow returned to the expected range after a valve adjustment.'],
    recommended_actions: [{ type: 'none' }]
  },
  {
    event_id: 'demo-temperature-04', system_id: 'south-hub', facility_id: 'south-pad',
    asset_id: 'south-well-1', category: 'temperature', status: 'ongoing',
    onset_unix: 1791525600, confidence: 0.79, est_cost: 9800,
    hours_to_limit: { p10: 18, p50: 31, p90: 48 },
    evidence: ['Wellhead temperature is rising against the daily baseline.'],
    recommended_actions: [{ type: 'field_inspection', asset_id: 'south-well-1' }]
  }
];
const DEMO_NETWORK = {
  defaults: { tank_limits_pct: { shutdown: 95 } },
  systems: [
    {
      id: 'north-hub', name: 'North Hub', center: [31.85, -102.3], truck_yard: [31.80, -102.40], disposal_site: { id: 'north-disposal', lat: 31.87, lon: -102.295 }, facilities: [{
        id: 'north-pad', name: 'North Pad', lat: 31.912, lon: -102.34,
        tank: { id: 'north-tank-1', capacity_bbl: 12000, start_level_pct: 63 },
        pump: { id: 'north-pump-a', max_flow_bpd: 18000 },
        pipeline: { id: 'north-pipeline-1', max_pressure_psi: 950 },
        wells: [{ id: 'north-well-1', oil_bpd: 4200, baseline_water_bpd: 700 }]
      }]
    },
    {
      id: 'south-hub', name: 'South Hub', center: [31.4, -102.8], truck_yard: [31.35, -102.9], disposal_site: { id: 'south-disposal', lat: 31.41, lon: -102.8 }, facilities: [{
        id: 'south-pad', name: 'South Pad', lat: 31.44, lon: -102.85,
        tank: { id: 'south-tank-1', capacity_bbl: 9000, start_level_pct: 48 },
        pump: { id: 'south-pump-a', max_flow_bpd: 14000 },
        pipeline: { id: 'south-pipeline-1', max_pressure_psi: 800 },
        wells: [{ id: 'south-well-1', oil_bpd: 3600, baseline_water_bpd: 520 }]
      }]
    }
  ]
};
const DEMO_API = {
  async discoverDatasets() {
    return [
      { path: 'demo/watchpot-live-shift', url: 'demo://watchpot-live-shift', group: 'Practice' },
      { path: 'demo/watchpot-resolved-history', url: 'demo://watchpot-resolved-history', group: 'Practice' }
    ];
  },
  async validateFolder(path) { return path.startsWith('demo/'); },
  async analyzeDataset(url) {
    return API.eventResult({ events: DEMO_EVENTS, as_of_unix: 1791536400, dataset_id: url.split('/').pop() });
  },
  async fetchNetwork() { return normalizeNetwork(DEMO_NETWORK); },
  async sortEvents(mode, context) {
    const events = DEMO_EVENTS.map(event => ({ ...event }));
    if (mode === 'recent') events.sort((a, b) => b.onset_unix - a.onset_unix);
    if (mode === 'cost') events.sort((a, b) => b.est_cost - a.est_cost);
    if (mode === 'priority') events.sort((a, b) => Number(b.status === 'ongoing') - Number(a.status === 'ongoing') || b.confidence - a.confidence);
    return API.eventResult({ events, as_of_unix: 1791536400, dataset_id: context.datasetId });
  },
  async exportEvents(context) { return { events: DEMO_EVENTS, dataset_id: context.datasetId, as_of_unix: 1791536400, mode: 'demo' }; },
  async directory() { return []; }
};
const ACTIVE_API = DEMO_MODE ? DEMO_API : API;

/* NORMALIZATION — explicit aliases and schema mappings only. */
function timestamp(epoch, iso) {
  const number = finite(epoch);
  if (number !== null && Number.isFinite(new Date(number * 1000).getTime())) return number;
  if (typeof iso === 'string' && iso.trim()) { const parsed = Date.parse(iso); if (Number.isFinite(parsed)) return parsed / 1000; }
  return null;
}
function normalizeEvent(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new Error('Each event must be a detailed event object.');
  const eventId = idOf(raw.event_id ?? raw.id);
  if (!eventId) throw new Error('An event is missing event_id. Stable event IDs are required.');
  return {
    ...raw, event_id: eventId, system_id: idOf(raw.system_id ?? raw.system),
    facility_id: idOf(raw.facility_id ?? raw.facility), asset_id: idOf(raw.asset_id ?? raw.asset),
    category: typeof raw.category === 'string' ? raw.category : 'other',
    status: typeof raw.status === 'string' ? raw.status.toLowerCase() : 'unknown',
    onset_unix: timestamp(raw.onset_unix, raw.onset_ts ?? raw.onset),
    end_unix: timestamp(raw.end_unix, raw.end_ts ?? raw.end),
    confidence: finite(raw.confidence), level_pct: finite(raw.level_pct), est_cost: finite(raw.est_cost),
    hours_to_limit: raw.hours_to_limit && typeof raw.hours_to_limit === 'object' ? {
      p50: finite(raw.hours_to_limit.p50), p10: finite(raw.hours_to_limit.p10), p90: finite(raw.hours_to_limit.p90)
    } : null,
    evidence: Array.isArray(raw.evidence) ? raw.evidence.filter(value => typeof value === 'string') : typeof raw.evidence === 'string' ? [raw.evidence] : [],
    recommended_actions: Array.isArray(raw.recommended_actions) ? raw.recommended_actions.filter(value => value && typeof value === 'object') : []
  };
}
function normalizeNetwork(raw) {
  const network = raw?.network ?? raw;
  if (!network || !Array.isArray(network.systems)) throw new Error('Expected a network object with a systems array. Align the network response contract.');
  const seen = new Set();
  function equipment(raw, kind) {
    if (!raw || !idOf(raw.id)) throw new Error(`Network ${kind} is missing its ID.`);
    const id = String(raw.id);
    if (seen.has(id)) throw new Error(`Duplicate network ID: ${id}.`);
    seen.add(id); return { ...raw, id };
  }
  return { ...network, systems: network.systems.map(rawSystem => {
    const system = equipment(rawSystem, 'system');
    if (!Array.isArray(system.facilities)) throw new Error(`System ${system.id} requires a facilities array.`);
    return { ...system, disposal_site: system.disposal_site ? equipment(system.disposal_site, 'disposal') : null,
      facilities: system.facilities.map(rawFacility => {
        const facility = equipment(rawFacility, 'facility');
        return { ...facility, wells: Array.isArray(facility.wells) ? facility.wells.map(well => equipment(well, 'well')) : [],
          tank: facility.tank ? equipment(facility.tank, 'tank') : null,
          pump: facility.pump ? equipment(facility.pump, 'pump') : null,
          pipeline: facility.pipeline ? equipment(facility.pipeline, 'pipeline') : null };
      }) };
  }) };
}

/* STATE AND SELECTORS */
const state = {
  choices: [], selectedFolder: null, loaded: null, network: null, events: [], orderedIds: [],
  status: 'ongoing', sort: 'priority', confirmedSort: null, scope: 'network', systemId: null,
  facilityId: null, assetId: null, eventId: null, viewports: new Map(), facilityFits: new Map(), bounds: null,
  loading: false, datasetError: null, networkError: null, sortError: null,
  datasetGeneration: 0, sortGeneration: 0, pendingDataset: null, datasetTask: null, sortTask: null, exportGeneration: 0
};
function systemById(id = state.systemId) { return state.network?.systems.find(system => system.id === id); }
function facilityById(id = state.facilityId) {
  for (const system of state.network?.systems ?? []) { const facility = system.facilities.find(item => item.id === id); if (facility) return { system, facility }; }
  return null;
}
function scopeEvents() {
  return state.events.filter(event => state.scope === 'network' || (event.system_id === state.systemId && (state.scope !== 'facility' || event.facility_id === state.facilityId)));
}
function visibleEvents() {
  const events = new Map(scopeEvents().filter(event => state.status === 'all' || event.status === state.status).map(event => [event.event_id, event]));
  return state.orderedIds.filter(id => events.has(id)).map(id => events.get(id));
}
function selectedEvent() { return state.events.find(event => event.event_id === state.eventId); }
function ongoing(events) { return events.filter(event => event.status === 'ongoing'); }
function viewKey() { return `${state.scope}:${state.systemId ?? ''}:${state.facilityId ?? ''}`; }
function clearInvisibleSelection() {
  if (state.eventId && !visibleEvents().some(event => event.event_id === state.eventId)) { state.eventId = null; state.assetId = null; }
}

/* FORMATTING AND SAFE DOM BUILDERS */
const numberFormat = new Intl.NumberFormat('en-US', { maximumFractionDigits: 1 });
const moneyFormat = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });
const dateFormat = new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
function dateText(value) { return value === null || value === undefined ? 'Unavailable' : `${dateFormat.format(new Date(value * 1000))} UTC`; }
function categoryText(category) { return ({ pump_failure: 'Pump failure', pump_degradation: 'Pump degradation', pipeline_leak: 'Pipeline leak', other: 'Other incident' })[category] ?? 'Other incident'; }
function costText(value) { return value === null ? 'Cost unavailable' : moneyFormat.format(value); }
function countdownText(value) {
  if (value === null || value === undefined) return null;
  if (value <= 0) return 'At shutdown limit';
  const minutes = Math.floor(value * 60 + 1e-9);
  if (!minutes) return '<1 min';
  return `${minutes >= 60 ? `${Math.floor(minutes / 60)}h ` : ''}${minutes % 60}m`;
}
function failureCountdown(event) { return event.status === 'ongoing' && event.category === 'pump_failure' ? countdownText(event.hours_to_limit?.p50) : null; }
function actionText(action) {
  switch (action.type) {
    case 'dispatch_truck': {
      const trucks = finite(action.trucks), trips = finite(action.trips_per_truck);
      return trucks !== null && trips !== null ? `Send ${numberFormat.format(trucks)} truck${trucks === 1 ? '' : 's'} · ${numberFormat.format(trips)} trip${trips === 1 ? '' : 's'} per truck` : 'Truck response · details unavailable';
    }
    case 'shut_in_wells': {
      const wells = Array.isArray(action.wells) ? action.wells.map(String) : [];
      const start = finite(action.start_after_min);
      return `Shut in ${wells.length ? wells.join(', ') : 'wells (IDs unavailable)'} · ${start === null || start === 0 ? 'Now' : `In ${numberFormat.format(start)} min`}`;
    }
    case 'field_inspection': return `Inspect ${assetKind(action.asset_id)}${action.asset_id ? ` · ${String(action.asset_id)}` : ''}`;
    case 'none': return 'No intervention recommended';
    default: return 'Recommendation unavailable';
  }
}
function planSummary(event) {
  if (event.recommended_actions.length) return event.recommended_actions.map(actionText).join(' + ');
  return typeof event.reference_action === 'string' ? event.reference_action : 'Recommendation unavailable';
}
function assetKind(id) {
  for (const system of state.network?.systems ?? []) {
    if (system.disposal_site?.id === id) return 'disposal site';
    for (const facility of system.facilities) {
      for (const kind of ['tank', 'pump', 'pipeline']) if (facility[kind]?.id === id) return kind;
      if (facility.wells.some(well => well.id === id)) return 'well';
    }
  }
  return 'equipment';
}
function el(tag, className, text) { const node = document.createElement(tag); if (className) node.className = className; if (text !== undefined) node.textContent = text; return node; }
function button(text, onClick, className) { const node = el('button', className, text); node.type = 'button'; node.addEventListener('click', onClick); return node; }
function empty(container, title, message, action) {
  const box = el('div', 'empty'); box.append(el('strong', '', title), el('div', '', message)); if (action) box.append(action); container.append(box);
}
function facts(container, entries) {
  const list = el('dl', 'facts'); entries.forEach(([label, value]) => { list.append(el('dt', '', label), el('dd', '', value)); }); container.append(list);
}
function section(title) { const node = el('section', 'inspector-section'); node.append(el('h4', '', title)); return node; }

/* DATASET DISCOVERY AND SERIALIZED ANALYSIS */
let discovering = false;
async function discover() {
  if (!DEMO_MODE) {
    if (state.selectedFolder) selectDataset(state.selectedFolder);
    else { $('manual').open = true; $('manual-path').focus(); }
    return;
  }
  if (discovering) return;
  discovering = true; $('refresh').disabled = true;
  const previousPaths = new Set(state.choices.map(choice => choice.path));
  $('discovery-status').className = '';
  $('discovery-status').textContent = 'Refreshing dataset choices…';
  try {
    const choices = await ACTIVE_API.discoverDatasets();
    state.choices = choices;
    if (state.selectedFolder && !choices.some(choice => choice.path === state.selectedFolder.path)) state.choices.push(state.selectedFolder);
    renderDatasets();
    const added = state.loaded ? choices.filter(choice => !previousPaths.has(choice.path)).length : 0;
    $('discovery-status').textContent = added ? `${added} new dataset${added === 1 ? '' : 's'} available. Choose a recording to analyze.` : choices.length ? 'Demo recordings ready' : 'No eligible datasets found. Enter a folder manually.';
    if (!state.selectedFolder && choices.length) selectDataset(choices.find(choice => choice.path === PREFERRED_DATASET) ?? choices[0]);
    else if (!choices.length && !state.loaded) $('load-status').textContent = 'No recording selected.';
  } catch (error) {
    $('discovery-status').textContent = error.message; $('discovery-status').className = 'error';
    $('manual').open = true; if (!state.loaded) $('load-status').textContent = 'Select a recording folder to begin.';
    renderDatasets();
  } finally { discovering = false; $('refresh').disabled = false; }
}
function renderDatasets() {
  const select = $('dataset'); select.replaceChildren();
  if (!state.choices.length) { const option = el('option', '', 'Paste a dataset folder link below'); select.append(option); select.disabled = true; return; }
  select.disabled = false;
  for (const group of ['Practice', 'Judging', 'Manual']) {
    const choices = state.choices.filter(choice => choice.group === group); if (!choices.length) continue;
    const optgroup = el('optgroup'); optgroup.label = group;
    choices.forEach(choice => { const option = el('option', '', choice.path); option.value = choice.path; option.selected = choice.path === state.selectedFolder?.path; optgroup.append(option); }); select.append(optgroup);
  }
}
function selectDataset(choice) {
  if (!choice) return;
  state.selectedFolder = choice; state.pendingDataset = { choice, generation: ++state.datasetGeneration };
  state.sortGeneration++; state.exportGeneration++; state.loading = true; state.datasetError = null;
  $('export-status').replaceChildren(); renderDatasets(); renderLoadStatus();
  if (!state.datasetTask) state.datasetTask = processDatasets().finally(() => { state.datasetTask = null; });
}
async function processDatasets() {
  if (state.sortTask) await state.sortTask;
  while (state.pendingDataset) {
    const { choice, generation } = state.pendingDataset; state.pendingDataset = null;
    try {
      const result = await ACTIVE_API.analyzeDataset(choice.url);
      if (generation !== state.datasetGeneration) continue;
      const context = { folderUrl: choice.url, datasetId: result.datasetId, path: choice.path, asOf: result.asOf, revision: result.revision };
      let network = null, networkError = null;
      try { network = await ACTIVE_API.fetchNetwork(context); } catch (error) { networkError = error.message; }
      if (generation !== state.datasetGeneration) continue;
      let sorted = result, confirmedSort = null, sortError = null;
      // Coalesce sort changes during reads; backend state remains serialized.
      while (true) {
        const mode = state.sort, sortGeneration = state.sortGeneration;
        try { sorted = await ACTIVE_API.sortEvents(mode, context); confirmedSort = mode; sortError = null; }
        catch (error) { sortError = `${mode} sort failed: ${error.message}`; }
        if (generation !== state.datasetGeneration || sortGeneration === state.sortGeneration) break;
      }
      if (generation !== state.datasetGeneration) continue;
      state.loaded = context; state.network = network; state.networkError = networkError;
      state.events = sorted.events; state.orderedIds = sorted.events.map(event => event.event_id);
      state.confirmedSort = confirmedSort; state.sortError = sortError;
      state.scope = 'network'; state.systemId = state.facilityId = state.assetId = state.eventId = null;
      state.viewports.clear(); state.facilityFits.clear(); GeoMap.reset(); state.loading = false; state.datasetError = null;
      renderAll();
    } catch (error) {
      if (generation !== state.datasetGeneration) continue;
      state.loading = false; state.datasetError = error.message; renderAll();
    }
  }
  state.loading = false; renderLoadStatus();
}
async function requestSort(mode) {
  state.sort = mode; state.sortGeneration++; renderSort();
  if (state.loading || !state.loaded || state.datasetError) return;
  if (!state.sortTask) state.sortTask = processSorts().finally(() => { state.sortTask = null; });
}
async function processSorts() {
  while (!state.loading) {
    const generation = state.sortGeneration, datasetGeneration = state.datasetGeneration, mode = state.sort;
    $('sort-status').textContent = `Sorting by ${mode}…`;
    try {
      const result = await ACTIVE_API.sortEvents(mode, state.loaded);
      if (datasetGeneration !== state.datasetGeneration) return;
      if (generation !== state.sortGeneration) continue;
      state.events = result.events; state.orderedIds = result.events.map(event => event.event_id);
      state.confirmedSort = mode; state.sortError = null; clearInvisibleSelection(); renderEvents(); renderInspector(); renderDiagram();
    } catch (error) {
      if (datasetGeneration !== state.datasetGeneration) return;
      if (generation !== state.sortGeneration) continue;
      state.sortError = `${mode} sort failed. Keeping ${state.confirmedSort ?? 'analysis'} order. ${error.message}`;
    }
    renderSort(); break;
  }
}
async function manualDataset(event) {
  event.preventDefault();
  const input = $('manual-path').value.trim();
  let path, url;
  try {
    if (/^https?:/i.test(input)) {
      const parsed = new URL(input);
      const parts = parsed.pathname.split('/').filter(Boolean);
      if (parsed.protocol !== 'https:' || parsed.hostname !== 'github.com' || parsed.username || parsed.password ||
          parts.length < 5 || parts[2] !== 'tree' || parts.some(part => part === '.' || part === '..')) throw new Error();
      url = `${parsed.origin}${parsed.pathname.replace(/\/$/, '')}`;
      path = decodeURIComponent(parts.slice(4).join('/'));
    } else {
      path = input.replace(/^\/+|\/+$/g, '');
      if (!path || path.split('/').some(part => !part || part === '.' || part === '..' || !/^[\w. -]+$/.test(part))) throw new Error();
      url = folderUrl(path);
    }
  } catch {
    $('manual-status').textContent = 'Paste an HTTPS GitHub folder link containing /tree/<branch>/<folder>, or a relative challenge folder path.'; return;
  }
  const submit = event.currentTarget.querySelector('button'); submit.disabled = true;
  let validated;
  try { validated = await ACTIVE_API.validateFolder(path); }
  catch { validated = null; }
  submit.disabled = false;
  if (validated === false) { $('manual-status').textContent = 'Folder is accessible but is missing one or more required input files.'; return; }
  $('manual-status').textContent = validated ? 'All three input files validated.' : 'Analyzing the folder link with the Java backend…';
  const choice = { path, url, group: 'Manual' };
  const existing = state.choices.findIndex(item => item.url === url);
  if (existing < 0) state.choices.push(choice);
  selectDataset(existing < 0 ? choice : state.choices[existing]);
}

/* RENDERING — panels do not rerender while the viewport moves. */
function renderLoadStatus() {
  const status = $('load-status'); status.replaceChildren(); status.className = '';
  if (state.loading) {
    status.textContent = `Analyzing ${state.selectedFolder.path}…${state.loaded ? ` Showing previous results: ${state.loaded.path}.` : ''}`;
    if (state.loaded) status.className = 'stale';
  } else if (state.datasetError) {
    status.className = 'error'; status.append(document.createTextNode(`${state.datasetError}${state.loaded ? ` Previous results retained: ${state.loaded.path}.` : ''}`), button('Retry analysis', () => selectDataset(state.selectedFolder), 'retry'));
  } else if (state.loaded) status.textContent = `Recording: ${state.loaded.path}`;
  else status.textContent = 'Paste a GitHub dataset folder link below to begin.';
  $('analysis-time').textContent = state.loaded?.asOf != null ? dateText(state.loaded.asOf) : 'Analysis time unavailable';
  $('export').disabled = state.loading || !!state.datasetError || !state.loaded;
  $('diagram-caption-kind').textContent = state.scope === 'facility' ? 'Equipment schematic · not to scale' : 'Geographic positions · indicative connections';
  $('diagram-title').textContent = state.scope === 'network' ? 'Network overview' : state.scope === 'system' ? `${state.systemId} · facilities` : `${state.facilityId} · equipment`;
}
function renderSort() {
  document.querySelectorAll('[data-sort]').forEach(node => node.setAttribute('aria-pressed', String(node.dataset.sort === state.sort)));
  $('sort-status').replaceChildren();
  if (state.sortError) $('sort-status').append(document.createTextNode(state.sortError), button('Retry sort', () => requestSort(state.sort), 'retry'));
}
function renderEvents() {
  const scoped = scopeEvents();
  const counts = { ongoing: ongoing(scoped).length, resolved: scoped.filter(event => event.status === 'resolved').length, all: scoped.length };
  document.querySelectorAll('[data-status]').forEach(node => { node.setAttribute('aria-pressed', String(node.dataset.status === state.status)); node.querySelector('span').textContent = counts[node.dataset.status]; });
  $('ongoing-count').textContent = `${ongoing(state.events).length} ongoing total`;
  $('scope-label').textContent = state.scope === 'network' ? 'All systems' : state.scope === 'system' ? state.systemId : `${state.systemId} / ${state.facilityId}`;
  const list = $('event-list'); list.replaceChildren();
  const events = visibleEvents();
  if (!events.length) {
    const include = state.status === 'ongoing' && counts.resolved ? button('Include resolved', () => setStatus('all')) : null;
    empty(list, state.loaded ? 'No matching events' : 'No analysis loaded', state.loaded ? 'No active alert in this view does not establish equipment health.' : 'Choose a recording to inspect backend analysis.', include); return;
  }
  events.forEach(event => {
    const row = button('', () => selectEvent(event.event_id), `event-row${state.eventId === event.event_id ? ' selected' : ''}`);
    row.setAttribute('aria-pressed', String(state.eventId === event.event_id)); row.dataset.eventId = event.event_id;
    const top = el('div', 'event-top'); top.append(el('span', 'event-title', `${event.status === 'ongoing' ? '⚠ ' : ''}${categoryText(event.category)}`), el('span', `badge ${event.status}`, event.status));
    row.append(top, el('div', 'event-context', `${event.facility_id ?? 'Facility unavailable'} · ${event.system_id ?? 'System unavailable'} · ${event.event_id}`));
    row.append(el('div', 'event-plan', `${event.status === 'resolved' ? 'Historical: ' : ''}${planSummary(event)}`));
    const metrics = el('div', 'event-metrics'); const countdown = failureCountdown(event);
    if (countdown) metrics.append(el('span', 'countdown', `${countdown}${event.hours_to_limit.p50 > 0 ? ' to limit' : ''}`));
    metrics.append(el('span', 'quiet', costText(event.est_cost))); row.append(metrics, el('time', 'event-onset', `Onset ${dateText(event.onset_unix)}`)); list.append(row);
  });
}
function renderBreadcrumbs() {
  const nav = $('breadcrumbs'); nav.replaceChildren();
  const crumbs = [['Network', () => navigate('network')]];
  if (state.scope !== 'network') crumbs.push([state.systemId, () => navigate('system', state.systemId)]);
  if (state.scope === 'facility') crumbs.push([state.facilityId, () => navigate('facility', state.systemId, state.facilityId)]);
  crumbs.forEach(([label, action], index) => { if (index) nav.append(el('span', '', '/')); const node = button(label, action); if (index === crumbs.length - 1) node.setAttribute('aria-current', 'page'); nav.append(node); });
  $('diagram-hint').textContent = state.scope === 'network' ? 'Select a system to explore →' : state.scope === 'system' ? 'Select a facility to inspect →' : 'Select equipment for details';
}
function renderAll() { renderLoadStatus(); renderSort(); renderEvents(); renderBreadcrumbs(); renderDiagram(); renderInspector(); }
function renderActions(container, event) {
  const actions = section(event.status === 'resolved' ? 'Historical recommendations' : 'Recommended response');
  if (event.recommended_actions.length) event.recommended_actions.forEach(action => {
    const card = el('div', 'action-card', actionText(action));
    if (action.type === 'dispatch_truck' && action.facility_id) card.append(el('small', '', `Facility: ${action.facility_id}`));
    actions.append(card);
  });
  else actions.append(el('div', 'action-card', typeof event.reference_action === 'string' ? event.reference_action : 'Recommendation unavailable'));
  actions.append(el('p', 'advisory-note', event.status === 'resolved' ? 'Recorded advice for a resolved event.' : 'Advisory plan only. No action has been executed by this console.'));
  facts(actions, [['Implementation cost', costText(event.est_cost)]]); container.append(actions);
}
function renderInspector() {
  const panel = $('inspector-content'); panel.replaceChildren();
  const event = selectedEvent();
  if (event) {
    panel.append(el('div', 'eyebrow', `Incident · ${event.event_id}`), el('h3', '', categoryText(event.category)), el('p', 'identity', event.asset_id ?? 'Asset unavailable'), el('span', `badge ${event.status}`, event.status));
    const countdown = failureCountdown(event);
    if (countdown) {
      const metric = el('div', 'metric-block urgent'); metric.append(el('span', '', 'TIME TO SHUTDOWN LIMIT · P50'), el('strong', '', countdown), el('span', '', 'Relative to recording endpoint · does not tick'));
      const range = event.hours_to_limit;
      if (range.p10 !== null && range.p90 !== null) metric.append(el('small', '', `P10–P90: ${countdownText(range.p10)} – ${countdownText(range.p90)}`));
      panel.append(metric);
    }
    if (event.level_pct !== null) {
      const metric = el('div', 'metric-block'); metric.append(el('span', '', 'REPORTED TANK LEVEL'), el('strong', '', `${numberFormat.format(event.level_pct)}%`));
      const track = el('div', 'level-track'), fill = el('span'); fill.style.width = `${Math.max(0, Math.min(100, event.level_pct))}%`; track.append(fill); metric.append(track); panel.append(metric);
    }
    renderActions(panel, event);
    const evidence = section('Evidence');
    if (!event.evidence.length) evidence.append(el('p', 'quiet', 'No evidence supplied.'));
    else {
      const list = el('ul', 'evidence'); event.evidence.slice(0, 3).forEach(text => list.append(el('li', '', text))); evidence.append(list);
      if (event.evidence.length > 3) { const details = el('details'), more = el('ul', 'evidence'); details.append(el('summary', '', `Show ${event.evidence.length - 3} more evidence lines`)); event.evidence.slice(3).forEach(text => more.append(el('li', '', text))); details.append(more); evidence.append(details); }
    }
    panel.append(evidence);
    const details = section('Event facts');
    const entries = [['System', event.system_id ?? 'Unavailable'], ['Facility', event.facility_id ?? 'Unavailable'], ['Onset', dateText(event.onset_unix)]];
    if (event.status === 'resolved') entries.push(['Resolution', dateText(event.end_unix)]);
    if (event.confidence !== null) entries.push(['Reported confidence score', numberFormat.format(event.confidence)]);
    facts(details, entries); panel.append(details); return;
  }
  if (state.assetId) {
    panel.append(el('div', 'eyebrow', 'Equipment'), el('h3', '', assetKind(state.assetId)), el('p', 'identity', state.assetId));
    const member = facilityById(); const facility = member?.facility; const system = member?.system ?? systemById();
    const asset = [facility?.tank, facility?.pump, facility?.pipeline, ...(facility?.wells ?? []), system?.disposal_site].find(item => item?.id === state.assetId);
    const specLabels = { capacity_bbl: 'Tank capacity (bbl)', max_flow_bpd: 'Max flow capacity (bpd)', max_bpd: 'Max throughput (bpd)', max_pressure_psi: 'Max pressure (psi)', start_level_pct: 'Start setpoint (%)', stop_level_pct: 'Stop setpoint (%)', baseline_water_bpd: 'Baseline water (bpd)', oil_bpd: 'Baseline oil (bpd)', gas_mcfd: 'Baseline gas (mcfd)', pressure_psi: 'Baseline pressure (psi)' };
    const specs = Object.entries(specLabels).filter(([key]) => finite(asset?.[key]) !== null).map(([key, label]) => [label, numberFormat.format(asset[key])]);
    const details = section('Network specifications'); if (specs.length) facts(details, specs); else details.append(el('p', 'quiet', 'No specifications supplied.')); details.append(el('p', 'advisory-note', 'Configuration and baselines, not live telemetry.')); panel.append(details);
    const related = scopeEvents().filter(item => item.asset_id === state.assetId); appendContextEvents(panel, related); return;
  }
  panel.append(el('div', 'eyebrow', state.scope === 'network' ? 'All systems' : state.scope), el('h3', '', state.scope === 'network' ? 'Network overview' : state.scope === 'system' ? state.systemId : state.facilityId));
  const events = scopeEvents(), counts = el('div', 'status-summary');
  for (const [label, count] of [['Ongoing', ongoing(events).length], ['Resolved', events.filter(item => item.status === 'resolved').length]]) { const cell = el('span', '', label); cell.prepend(el('strong', '', count)); counts.append(cell); } panel.append(counts);
  if (state.scope === 'network') {
    panel.append(el('p', 'quiet', `${state.network?.systems.length ?? 0} systems in this recording. Select a system to explore its facilities, or select an event to inspect the affected asset.`));
    const categories = section('Events by category');
    for (const category of [...new Set(events.map(item => item.category))]) { const subset = events.filter(item => item.category === category); const row = el('div', 'category-count'); row.append(el('span', '', categoryText(category)), el('span', '', `${ongoing(subset).length} ongoing / ${subset.filter(item => item.status === 'resolved').length} resolved`)); categories.append(row); } panel.append(categories);
  } else {
    panel.append(el('p', 'quiet', state.scope === 'system' ? 'Facilities feed this system’s shared disposal site. Select a facility to explore its equipment.' : 'Select equipment for specifications and associated events. Connections represent network membership.'));
    const threshold = finite(state.network?.defaults?.tank_limits_pct?.shutdown);
    if (threshold !== null) { const info = section('Network setpoint'); facts(info, [['Tank shutdown threshold', `${numberFormat.format(threshold)}%`]]); panel.append(info); }
    appendContextEvents(panel, ongoing(events));
  }
}
function appendContextEvents(panel, events) {
  const list = section(state.assetId ? 'Associated incidents' : 'Ongoing responses');
  if (!events.length) list.append(el('p', 'quiet', 'No active alert. Equipment health is not established by the absence of an alert.'));
  events.forEach(event => { const item = button(categoryText(event.category), () => selectEvent(event.event_id), 'context-event'); item.append(el('small', '', `${event.event_id} · ${event.status} · ${planSummary(event)}`)); list.append(item); }); panel.append(list);
}

/* DIAGRAM LAYOUT — deterministic membership-based layouts, inline SVG only. */
const SVG_NS = 'http://www.w3.org/2000/svg';
function svg(tag, attrs = {}, text) { const node = document.createElementNS(SVG_NS, tag); Object.entries(attrs).forEach(([key, value]) => node.setAttribute(key, value)); if (text !== undefined) node.textContent = text; return node; }
function icon(kind, level = null) {
  const group = svg('g', { class: 'equipment-icon', transform: 'translate(0,-6)' });
  const paths = {
    system: 'M-20-16H-5V-2H-20ZM5-16H20V-2H5ZM-8 10H8V24H-8ZM-12-2V4H12V-2M0 4V10',
    facility: 'M-23 20H23M-17 20V-4H0V20M0 20V-17H17V20M-12 3H-6M-12 10H-6M6-8H11M6 0H11M6 8H11',
    well: 'M-22 20H22M-8 20L-2-6H3L10 20M-15-7H17M-12-7L-6-18H12L18-7M3-7V6M-15-7V10M-19 10H-11',
    pump: 'M-22 19H22M-15 19V7M15 19V7M-25-4H-15M15-4H25M-7-14L10-4L-7 6Z',
    pipeline: 'M-24-7H-7V-17H8V-7H24V7H8V17H-7V7H-24ZM-17-11V11M17-11V11',
    disposal: 'M-24 20H24M-15 20V-12H15V20M-19-12H19L0-24ZM-7-4H7M-7 4H7M-7 12H7'
  };
  if (kind === 'tank') {
    if (level !== null) group.append(svg('rect', { x: -18, y: 23 - Math.max(0, Math.min(100, level)) * .46, width: 36, height: Math.max(0, Math.min(100, level)) * .46, class: 'tank-fill', 'clip-path': 'url(#tank-clip)' }));
    group.append(svg('rect', { x: -18, y: -23, width: 36, height: 46, rx: 4 }), svg('path', { d: 'M-18-12Q0-4 18-12M-18 12Q0 20 18 12' }));
  } else { if (kind === 'pump') group.append(svg('circle', { cx: 0, cy: -4, r: 16 })); group.append(svg('path', { d: paths[kind] ?? paths.facility })); }
  return group;
}
function nodeCard({ id, kind, x, y, title, subtitle, alert, onClick, width = 176, level = null, extra = null }) {
  const group = svg('g', { class: `node${state.assetId === id || (state.scope === 'network' && state.systemId === id) ? ' selected' : ''}${alert ? ' alert' : ''}`, transform: `translate(${x},${y})`, tabindex: 0, role: 'button', 'aria-label': `${title}. ${subtitle}. ${alert ? `${alert} ongoing alerts.` : 'No active alert.'}`, 'data-node-id': id });
  group.append(svg('title', {}, `${title} — ${subtitle}`));
  group.append(svg('rect', { class: 'node-box', x: -width / 2, y: -73, width, height: extra ? 170 : 146, rx: 7 }));
  if (kind === 'system') group.append(svg('rect', { class: 'system-accent', x: -width / 2 + 14, y: -60, width: 20, height: 3, rx: 1 }));
  group.append(icon(kind, level), svg('text', { class: 'node-label', x: 0, y: 36, 'text-anchor': 'middle' }, title), svg('text', { class: 'node-sub', x: 0, y: 54, 'text-anchor': 'middle' }, subtitle));
  if (title.length > 23) {
    const label = group.querySelector('.node-label'); label.textContent = '';
    const chunks = title.match(/.{1,22}/g);
    chunks.slice(0, 2).forEach((chunk, index) => label.append(svg('tspan', { x: 0, dy: index ? 15 : -7 }, index === 1 && chunks.length > 2 ? `${chunk.slice(0, 18)}…` : chunk)));
  }
  if (extra) group.append(svg('text', { class: 'node-alert', x: 0, y: 79, 'text-anchor': 'middle' }, extra));
  if (alert) { const marker = svg('g', { class: 'alert-marker', transform: `translate(${width / 2 - 12},-62)` }); marker.append(svg('circle', { r: 11 }), svg('text', { y: 4 }, '!')); group.append(marker); }
  group.addEventListener('click', event => { if (suppressNodeClick) { event.preventDefault(); return; } onClick(); });
  group.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); onClick(); } });
  $('viewport').append(group);
}
function flow(x1, y1, x2, y2, bent = false) {
  const d = bent ? `M${x1} ${y1}H${(x1 + x2) / 2}V${y2}H${x2}` : `M${x1} ${y1}L${x2} ${y2}`;
  $('viewport').append(svg('path', { class: 'flow', d }));
}
function renderDiagram() {
  const viewport = $('viewport'); viewport.replaceChildren(); $('diagram-message').replaceChildren();
  state.bounds = null;
  if (!state.network) {
    GeoMap.hide();
    const message = $('diagram-message'); message.append(el('strong', '', state.networkError ? 'Schematic unavailable' : 'Choose a recording to explore the network'), el('p', '', state.networkError ?? 'Systems and equipment will appear after backend analysis.'));
    if (state.networkError) message.append(button('Retry network', retryNetwork)); return;
  }
  if (state.scope !== 'facility') { GeoMap.render(); return; }
  GeoMap.hide(); layoutFacility();
  if (!state.bounds) return;
  if (state.viewports.has(viewKey())) applyTransform(); else fitDiagram();
}
function facilityMetrics(facility) {
  const events = ongoing(state.events.filter(event => event.facility_id === facility.id));
  const level = events.find(event => event.level_pct !== null)?.level_pct ?? null;
  const failure = events.find(event => failureCountdown(event));
  return { events, level, extra: [level !== null ? `${numberFormat.format(level)}% tank` : '', failure ? `${failureCountdown(failure)} to limit` : ''].filter(Boolean).join(' · ') };
}
function layoutFacility() {
  const member = facilityById(); if (!member) { $('diagram-message').textContent = 'The selected facility is not present in the network response. Events remain available.'; return; }
  const { facility, system } = member, height = Math.max(300, facility.wells.length * 180), centerY = height / 2;
  const chain = [['tank', facility.tank, 355], ['pump', facility.pump, 585], ['pipeline', facility.pipeline, 815], ['disposal', system.disposal_site, 1045]].filter(([, asset]) => asset);
  // Missing components break connections rather than inventing a bypass.
  if (facility.tank) facility.wells.forEach((well, index) => flow(188, 95 + index * 180, 267, centerY, true));
  for (let index = 0; index < chain.length - 1; index++) {
    const [kind, , x] = chain[index], [nextKind, , nextX] = chain[index + 1];
    if ((kind === 'tank' && nextKind === 'pump') || (kind === 'pump' && nextKind === 'pipeline') || (kind === 'pipeline' && nextKind === 'disposal')) flow(x + 88, centerY, nextX - 88, centerY);
  }
  const assets = [...facility.wells.map((well, index) => ['well', well, 100, 95 + index * 180]), ...chain.map(([kind, asset, x]) => [kind, asset, x, centerY])];
  const metrics = facilityMetrics(facility);
  assets.forEach(([kind, asset, x, y]) => {
    const events = ongoing(state.events.filter(event => event.asset_id === asset.id));
    const level = kind === 'tank' ? metrics.level : null;
    const threshold = finite(state.network.defaults?.tank_limits_pct?.shutdown);
    const subtitle = kind === 'tank' && level !== null ? `${numberFormat.format(level)}% · limit ${threshold === null ? 'unavailable' : `${threshold}%`}` : kind === 'disposal' ? 'Shared disposal site' : `${events.length ? `${events.length} ongoing` : 'No active alert'}`;
    nodeCard({ id: asset.id, kind, x, y, title: asset.id, subtitle, alert: events.length, level, onClick: () => selectAsset(asset.id) });
  });
  state.bounds = { x: 0, y: 0, width: 1160, height: height + 10 };
}

/* NAVIGATION, EXPORT, AND VIEWPORT INTERACTIONS */
function navigate(scope, systemId = null, facilityId = null) {
  GeoMap.remember();
  state.scope = scope; state.systemId = systemId; state.facilityId = facilityId; state.assetId = state.eventId = null;
  clearInvisibleSelection(); renderAll();
}
function selectEvent(id) {
  const event = state.events.find(item => item.event_id === id); if (!event) return;
  const member = facilityById(event.facility_id);
  GeoMap.remember();
  state.eventId = id; state.assetId = event.asset_id; state.systemId = member?.system.id ?? event.system_id; state.facilityId = event.facility_id;
  state.scope = event.facility_id ? 'facility' : event.system_id ? 'system' : 'network';
  if (state.status !== 'all' && state.status !== event.status) state.status = 'all';
  renderAll(); revealSelectedAsset(); openInspector();
}
function selectAsset(id) { state.assetId = id; state.eventId = null; renderEvents(); renderInspector(); renderDiagram(); openInspector(); }
function setStatus(status) { state.status = status; clearInvisibleSelection(); renderEvents(); renderInspector(); renderDiagram(); }
function openInspector() { $('inspector').classList.remove('collapsed'); $('inspector-toggle').setAttribute('aria-expanded', 'true'); }
async function retryNetwork() {
  if (state.loading || !state.loaded || state.datasetError) return;
  const generation = state.datasetGeneration;
  $('diagram-message').textContent = 'Loading network…';
  try { const network = await ACTIVE_API.fetchNetwork(state.loaded); if (generation !== state.datasetGeneration) return; state.network = network; state.networkError = null; }
  catch (error) { if (generation !== state.datasetGeneration) return; state.networkError = error.message; }
  renderDiagram(); renderInspector();
}
let exportBlobUrl = null;
async function exportEvents() {
  if (state.loading || state.datasetError || !state.loaded) return;
  const generation = ++state.exportGeneration, datasetGeneration = state.datasetGeneration;
  $('export').disabled = true; $('export-status').textContent = 'Preparing export link…';
  try {
    const data = await ACTIVE_API.exportEvents(state.loaded);
    if (generation !== state.exportGeneration || datasetGeneration !== state.datasetGeneration) return;
    if (!Array.isArray(data.events) || data.dataset_id !== state.loaded.datasetId) throw new Error('Export does not match the selected dataset. Retry analysis.');
    if (exportBlobUrl) URL.revokeObjectURL(exportBlobUrl);
    exportBlobUrl = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }));
    const link = el('a', '', `Download events · ${state.loaded.path}`); link.href = exportBlobUrl; link.download = 'watchpot-events.json';
    $('export-status').replaceChildren(document.createTextNode('Export ready.'), link); link.click();
  } catch (error) {
    if (generation !== state.exportGeneration || datasetGeneration !== state.datasetGeneration) return;
    $('export-status').replaceChildren(document.createTextNode(`Export failed: ${error.message}`), button('Retry export', exportEvents, 'retry'));
  } finally { if (generation === state.exportGeneration) renderLoadStatus(); }
}
function transform() { if (!state.viewports.has(viewKey())) state.viewports.set(viewKey(), { x: 0, y: 0, scale: 1 }); return state.viewports.get(viewKey()); }
function applyTransform() {
  const value = transform();
  $('viewport').setAttribute('transform', `translate(${value.x},${value.y}) scale(${value.scale})`);
  $('zoom-value').textContent = `${Math.round(value.scale * 100)}%`;
  // Compensate labels at fitted scales; zoom remains available for very large networks.
  $('viewport').querySelectorAll('.node-label').forEach(node => node.style.fontSize = `${Math.min(20, Math.max(12, 12 / value.scale))}px`);
  $('viewport').querySelectorAll('.node-sub').forEach(node => node.style.fontSize = `${Math.min(18, Math.max(10, 11 / value.scale))}px`);
}
function fitDiagram() {
  if (state.network && state.scope !== 'facility') { GeoMap.fit(); return; }
  if (!state.bounds) return;
  const rect = $('diagram-surface').getBoundingClientRect(), bounds = state.bounds;
  if (!rect.width || !rect.height) return;
  // Fit is allowed below the interactive 0.4 zoom floor so large networks remain visible.
  const scale = Math.min(1.15, Math.max(.08, Math.min((rect.width - 40) / bounds.width, (rect.height - 90) / bounds.height)));
  state.facilityFits.set(viewKey(), scale);
  state.viewports.set(viewKey(), { x: (rect.width - bounds.width * scale) / 2 - bounds.x * scale, y: (rect.height - 45 - bounds.height * scale) / 2 - bounds.y * scale, scale }); applyTransform();
}
let zoomFrame = null, zoomTarget = null;
function zoomAt(factor, x, y, smooth = false) {
  if (!state.bounds) return;
  const value = transform(), currentKey = viewKey();
  if (zoomTarget && zoomTarget.key !== currentKey) { cancelAnimationFrame(zoomFrame); zoomFrame = null; zoomTarget = null; }
  const base = zoomTarget?.key === currentKey ? zoomTarget.scale : value.scale;
  const requested = (smooth ? base : value.scale) * factor;
  if (state.scope === 'facility' && requested < (state.facilityFits.get(currentKey) ?? value.scale) * .65) {
    cancelAnimationFrame(zoomFrame); zoomFrame = null; zoomTarget = null; GeoMap.leaveFacility(); return;
  }
  const scale = Math.max(.08, Math.min(2.5, requested));
  const setScale = next => {
    const ratio = next / value.scale;
    value.x = x - (x - value.x) * ratio; value.y = y - (y - value.y) * ratio; value.scale = next; applyTransform();
  };
  if (!smooth || matchMedia('(prefers-reduced-motion: reduce)').matches) {
    cancelAnimationFrame(zoomFrame); zoomFrame = null; zoomTarget = null; setScale(scale); return;
  }
  zoomTarget = { key:currentKey, scale, x, y, updated:performance.now() };
  if (zoomFrame !== null) return;
  let previous = performance.now();
  const step = now => {
    if (!zoomTarget || viewKey() !== currentKey || transform() !== value) { zoomTarget = null; zoomFrame = null; return; }
    const elapsed = Math.min(64, Math.max(1, now-previous)); previous = now;
    x = zoomTarget.x; y = zoomTarget.y;
    const done = Math.abs(zoomTarget.scale-value.scale) < .0001 || now-zoomTarget.updated >= 70;
    setScale(done ? zoomTarget.scale : value.scale + (zoomTarget.scale-value.scale) * (1-Math.exp(-elapsed/18)));
    if (!done) zoomFrame = requestAnimationFrame(step);
    else { zoomTarget = null; zoomFrame = null; }
  };
  zoomFrame = requestAnimationFrame(step);
}
function zoomCenter(factor) { if (state.scope !== 'facility' && state.network) { GeoMap.zoom(factor > 1 ? .75 : -.75); return; } const rect = $('diagram-surface').getBoundingClientRect(); zoomAt(factor ** 1.5, rect.width / 2, rect.height / 2, true); }
function revealSelectedAsset() {
  const node = [...$('viewport').querySelectorAll('[data-node-id]')].find(item => item.getAttribute('data-node-id') === state.assetId); if (!node) return;
  const rect = node.getBoundingClientRect(), surface = $('diagram-surface').getBoundingClientRect(), value = transform();
  if (rect.left < surface.left || rect.right > surface.right || rect.top < surface.top || rect.bottom > surface.bottom - 60) {
    value.x += surface.left + surface.width / 2 - (rect.left + rect.width / 2);
    value.y += surface.top + (surface.height - 60) / 2 - (rect.top + rect.height / 2); applyTransform();
  }
}
const pointers = new Map(); let gesture = null, suppressNodeClick = false;
function pointerDistance() { const points = [...pointers.values()]; return Math.hypot(points[0].x - points[1].x, points[0].y - points[1].y); }
function pointerCenter() { const points = [...pointers.values()]; return { x: (points[0].x + points[1].x) / 2, y: (points[0].y + points[1].y) / 2 }; }
function setupViewport() {
  const surface = $('diagram-surface');
  surface.addEventListener('wheel', event => { if (state.scope !== 'facility') return; if (!state.bounds) return; event.preventDefault(); const rect = surface.getBoundingClientRect(); const delta = event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? rect.height : 1); zoomAt(Math.exp(-Math.max(-150, Math.min(150, delta)) * .00525), event.clientX - rect.left, event.clientY - rect.top, true); }, { passive: false });
  surface.addEventListener('pointerdown', event => {
    if (state.scope !== 'facility') return;
    if (event.target.closest('.zoom-controls') || event.target.closest('.diagram-message') || event.button !== 0) return;
    cancelAnimationFrame(zoomFrame); zoomFrame = null; zoomTarget = null;
    const rect = surface.getBoundingClientRect(); pointers.set(event.pointerId, { x: event.clientX - rect.left, y: event.clientY - rect.top });
    const point = pointers.get(event.pointerId); gesture = { start: point, previous: point, moved: false, distance: pointers.size === 2 ? pointerDistance() : null, center: pointers.size === 2 ? pointerCenter() : null };
    // Capture after movement so taps retain the equipment click target.
  });
  surface.addEventListener('pointermove', event => {
    if (!pointers.has(event.pointerId) || !gesture) return;
    const rect = surface.getBoundingClientRect(), point = { x: event.clientX - rect.left, y: event.clientY - rect.top }; pointers.set(event.pointerId, point);
    if (pointers.size === 2) {
      const distance = pointerDistance(), center = pointerCenter();
      if (gesture.distance > 0) zoomAt(distance / gesture.distance, gesture.center.x, gesture.center.y);
      if (state.scope !== 'facility') { pointers.clear(); gesture = null; return; }
      const value = transform(); if (gesture.center) { value.x += center.x - gesture.center.x; value.y += center.y - gesture.center.y; }
      gesture.distance = distance; gesture.center = center; gesture.moved = true; applyTransform();
    } else {
      if (!gesture.moved && Math.hypot(point.x - gesture.start.x, point.y - gesture.start.y) < 5) return;
      if (!gesture.moved) surface.setPointerCapture(event.pointerId);
      const value = transform(); value.x += point.x - gesture.previous.x; value.y += point.y - gesture.previous.y; gesture.moved = true; applyTransform();
    }
    gesture.previous = point; if (gesture.moved) { suppressNodeClick = true; surface.classList.add('dragging'); }
  });
  function endPointer(event) {
    pointers.delete(event.pointerId);
    if (surface.hasPointerCapture(event.pointerId)) surface.releasePointerCapture(event.pointerId);
    if (!pointers.size) { gesture = null; surface.classList.remove('dragging'); setTimeout(() => { suppressNodeClick = false; }, 0); }
    else { const point = [...pointers.values()][0]; gesture = { start: point, previous: point, moved: true, distance: null, center: null }; }
  }
  surface.addEventListener('pointerup', endPointer); surface.addEventListener('pointercancel', endPointer);
  let resizeFrame;
  new ResizeObserver(() => { cancelAnimationFrame(resizeFrame); resizeFrame = requestAnimationFrame(() => { if (state.scope !== 'facility') { GeoMap.resize(); return; } if (!state.bounds) return; fitDiagram(); revealSelectedAsset(); }); }).observe(surface);
}

/* INITIALIZATION */
$('mode-indicator').textContent = DEMO_MODE ? 'DEMO MODE · ?mode=demo' : 'LIVE API MODE';
$('mode-indicator').classList.toggle('demo', DEMO_MODE);
$('dataset').addEventListener('change', event => selectDataset(state.choices.find(choice => choice.path === event.target.value)));
$('refresh').addEventListener('click', discover);
$('manual-form').addEventListener('submit', manualDataset);
$('export').addEventListener('click', exportEvents);
$('status-filter').addEventListener('click', event => { const target = event.target.closest('[data-status]'); if (target) setStatus(target.dataset.status); });
$('sort-controls').addEventListener('click', event => { const target = event.target.closest('[data-sort]'); if (target) requestSort(target.dataset.sort); });
$('zoom-in').addEventListener('click', () => zoomCenter(1.2)); $('zoom-out').addEventListener('click', () => zoomCenter(1 / 1.2)); $('fit').addEventListener('click', fitDiagram);
$('inspector-toggle').addEventListener('click', () => { const hidden = $('inspector').classList.toggle('collapsed'); $('inspector-toggle').setAttribute('aria-expanded', String(!hidden)); });
$('inspector-close').addEventListener('click', () => { $('inspector').classList.add('collapsed'); $('inspector-toggle').setAttribute('aria-expanded', 'false'); $('inspector-toggle').focus(); });
document.querySelectorAll('[data-tab]').forEach(node => { if (node.tagName !== 'BUTTON') return; node.addEventListener('click', () => { document.querySelector('.workspace').dataset.tab = node.dataset.tab; document.querySelectorAll('button[data-tab]').forEach(tab => tab.setAttribute('aria-pressed', String(tab === node))); requestAnimationFrame(fitDiagram); }); });
document.addEventListener('keydown', event => { if (event.key === 'Escape') { state.eventId = state.assetId = null; renderEvents(); renderInspector(); renderDiagram(); } });
if (matchMedia('(max-width:1099px)').matches) { $('inspector').classList.add('collapsed'); $('inspector-toggle').setAttribute('aria-expanded', 'false'); }
setupViewport(); renderAll(); renderDatasets();
if (DEMO_MODE) discover();
else {
  $('manual').open = true;
  $('refresh').title = 'Reanalyze selected folder';
  $('refresh').setAttribute('aria-label', 'Reanalyze selected folder');
}
