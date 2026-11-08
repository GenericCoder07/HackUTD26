# Watchpot frontend

The Java server serves this plain HTML/CSS/JavaScript frontend. Run it from `Java Webserver` using the root README commands; no frontend build is required.

Live loading is explicit: paste a GitHub dataset folder link into **Load a GitHub dataset folder**, then click **Analyze folder**. The frontend posts the existing `github-repo` JSON field to `/api/getevents` and retrieves the current network from `/api/getnetwork`. There is no repository discovery request, startup data fetch, visibility refresh, or polling timer. The ↻ button reanalyzes the selected folder. Recording choices last for the current page session.

The existing Java backend is unchanged by this update. It stores configuration globally, replaces its working telemetry CSV, and recomputes events when requested. It does not provide dataset IDs, commit metadata, or analysis timestamps. Its sorting currently supports Recent only; live export returns an acknowledgement rather than an event payload. See the root README for these limitations.

`?mode=demo` uses built-in network/event fixtures without backend or GitHub requests. OpenStreetMap tiles remain optional online requests in either mode.

`js/geography.js` handles locally bundled Leaflet maps, geographic layers, system summary tooltips, saved cameras, continuous zoom, and tile fallback. `js/app.js` handles event panels, pasted links, the inspector, and equipment schematics. User text is rendered using text nodes. The logo is the original vector artwork embedded inline in `html/index.html` so the current static image handler needs no changes.

Systems use `center`; facilities/disposal sites use `lat`/`lon`; truck yards use coordinate arrays. Missing coordinates stay in the locations list. Connections indicate dataset membership, not surveyed pipeline routes; equipment remains schematic. Zoom navigation uses levels 11/15 for system/facility entry, 65% fitted facility scale for reversal, and level 9 for returning to the network. Markers and breadcrumbs remain explicit navigation options. Reduced-motion preferences are respected.

Leaflet's MIT license is in `vendor/leaflet/LICENSE`. OpenStreetMap attribution remains visible. The project's MIT license is preserved.
