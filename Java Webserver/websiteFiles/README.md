# Frontend and API handoff

The Java server serves `/`, `/css`, `/js`, `/images`, and the local Leaflet 1.9.4 bundle under `/vendor/leaflet`. Run the server from `Java Webserver` using the root README commands. No frontend build is needed.

## Modes and geography

Live mode is the default. Explicit `?mode=demo` uses in-memory event/network fixtures and never calls the backend or GitHub. OpenStreetMap tiles are optional in both modes; turn off Street map for a network-independent demo.

`js/geography.js` owns geographic cameras, marker/connection overlays, tile fallback, and zoom navigation. `js/app.js` retains event normalization, dataset selection, filters, equipment schematics, and the inspector. DOM labels are built with text nodes, including map labels sourced from datasets.

Systems use `center: [lat, lon]`; facilities and disposal sites use `lat`/`lon`; truck yards use `[lat, lon]`. Invalid or absent coordinates are listed explicitly. Equipment has no individual geographic coordinates in the challenge schema. Dashed facility-to-disposal links describe connectivity, not actual pipeline routes.

User zoom enters a nearby system at geographic zoom 11 and a nearby facility at zoom 15. The focus must be within 80 screen pixels of the target marker. Zoom out below 65% of the facility's fitted scale to return to its system at zoom 14, and out to zoom 9 to return to the network. Programmatic camera restoration, fitting, and resizing do not drill down. Parent cameras are preserved. Markers and the locations list remain available where locations overlap.

## Live contracts

Every successful JSON response includes `success: true` and `response: 200`; errors use the actual HTTP status plus `success: false` and `response-text`.

| Operation | Request | Payload |
| --- | --- | --- |
| Discover | `GET /api/datasets` | `{datasets: [{path, url, group}]}` |
| Analyze | `POST /api/getevents`, JSON `{"github-repo":"https://github.com/nihaal419/HackUTD-ZeroDay/tree/main/<folder>"}` | Analysis metadata and `{events: [...]}`; an empty array is valid |
| Network | `GET /api/getnetwork?dataset_id=<id>` | Same analysis metadata and `{network: ...}` |
| Sort | `GET /api/resortevents?dataset_id=<id>&pass-params=priority\|recent\|cost` | Complete event collection in requested order |
| Export | `GET /api/exportevents?dataset_id=<id>` | Complete event collection and metadata for browser JSON download |

Analysis metadata includes `dataset_id`, `dataset_path`, `source_revision`, `start_unix`, `as_of_unix`, and `as_of_ts`. Times come from telemetry. Each successful analysis gets a new ID, even for the same folder. Network/sort/export reject missing or outdated IDs with HTTP 409; the frontend advises reselecting the dataset.

The backend resolves a commit before fetching the three input files, computes in a temporary directory, and publishes the entire snapshot atomically. The latest started request wins; a failed request preserves the previously published snapshot. No analysis writes the repository's telemetry file. Sorts do not change global ordering or rerun the analyzer.

Dataset discovery has a shared 60-second server cache. An unchanged repository commit avoids fetching the tree again. Truncated recursive trees fall back to explicit directory traversal; incomplete listings produce an error. Rate-limit backoff respects GitHub response headers. Frontend checks run every 60 seconds while visible and never automatically switch an already selected recording.

## Attribution

Leaflet's license is bundled in `vendor/leaflet/LICENSE`. OpenStreetMap attribution remains visible while street tiles are displayed. Tiles are requested only for the current view, with ordinary browser caching; no offline tile archive or prefetch is offered. The source repository's MIT license is preserved.
