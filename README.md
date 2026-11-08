# Watchpot · HackUTD26

Watchpot is a hackathon operations dashboard for a produced-water collection network. It turns network configuration and sensor recordings into a geographic view of systems and facilities, detected incidents, tank shutdown countdowns, and advisory response plans.

Operators can locate a facility, inspect its equipment, review the evidence behind an incident, and compare response costs. The backend computes results from the selected recording; live mode does not read prewritten incident answers. Demo mode provides built-in sample events for presenting the interface without downloading a dataset.

## What the project does

- Visualizes systems, facilities, disposal sites, and truck yards at their supplied coordinates.
- Connects facilities to their disposal site and opens an equipment schematic for each facility.
- Detects pump failures, pump degradation, and pipeline leaks from telemetry.
- Estimates time until a failed facility's tank reaches its shutdown threshold.
- Compares bounded truck-dispatch and well-shut-in plans using configured costs and production values.
- Supports incident filtering, priority/recent/cost sorting, an evidence inspector, and JSON export.
- Discovers new dataset folders in the public challenge repository without replacing the operator's current analysis.

## Clone and run

Install **Git** and **Java JDK 17 or newer**. Open Terminal on Mac/Linux or PowerShell on Windows, then run each line:

```sh
git clone https://github.com/GenericCoder07/HackUTD26.git
cd "HackUTD26/Java Webserver"
javac --release 17 -sourcepath src -d bin src/WebServer/WebServer.java
java -cp bin WebServer.WebServer
```

Open **http://localhost:8080/** for real dataset analysis, or **http://localhost:8080/?mode=demo** for built-in sample events. Keep the terminal open; press **Ctrl+C** to stop. No Python, Node, API key, or frontend build step is required to run the app.

After changing Java code, stop the server, rerun the `javac` and `java` commands from `Java Webserver`, and refresh the browser. Changing HTML/CSS/JavaScript only requires refreshing the browser. These commands run your current checkout; share the updated code with teammates before expecting them to see local changes.

## Using the console

- Choose a recording from the dataset dropdown. Live mode discovers folders containing `network.json`, `ops.json`, and `telemetry.csv` anywhere in `nihaal419/HackUTD-ZeroDay` on `main`.
- New folders are checked at startup, every 60 seconds while visible, and with Refresh. A new folder appears in the dropdown; select it to compute its analysis. Existing results stay selected until you switch.
- The network map uses supplied coordinates. Zoom near a system to enter it, then near a facility to inspect its equipment. Zoom out to return. Markers, breadcrumbs, and **Locations and unmapped entries** also provide navigation.
- Dashed directional lines show facility-to-disposal membership. They are indicative connections; actual pipeline routes are not supplied. Wells, tanks, pumps, and pipelines use a labeled schematic inside each facility.
- Switch off **Street map** to keep the geographic overlay on a plain background. Tile failures automatically retain this overlay. The map library is bundled locally; surrounding map tiles require internet access.
- **Export events** downloads all computed events and the dataset metadata as JSON. Filters only affect the view.

Live datasets require GitHub access. If discovery is unavailable, enter the exact relative folder path manually. GitHub rate-limit errors show when to retry. If another browser tab replaces the server's current analysis, select your dataset again; stale results are never mixed with the new dataset.

## How the map works

The street map comes from **OpenStreetMap raster tiles**. The browser requests the visible map squares from `https://tile.openstreetmap.org/{z}/{x}/{y}.png`. **Leaflet 1.9.4** handles tile placement, panning, zoom controls, geographic projection, markers, and overlays. Leaflet's JavaScript, CSS, and images are bundled in this repository, so there is no CDN dependency or map API key.

There are two separate layers:

| Layer | Source | Purpose |
| --- | --- | --- |
| Streets and surrounding geography | OpenStreetMap's online tile service | Provides recognizable physical surroundings. |
| Systems, assets, incident colors, labels, and connections | The selected dataset and computed events | Shows the actual network being analyzed. |

System positions come from `center`; facilities, disposal sites, and truck yards use their supplied coordinates. The initial camera fits the loaded dataset, so it is not restricted to Texas. No address lookup or geocoding is needed. Invalid or missing coordinates stay accessible through **Locations and unmapped entries** rather than receiving invented positions.

Dashed arrows represent facility-to-disposal membership, **not surveyed pipeline routes**. The dataset does not supply equipment coordinates, so the facility view is a connected SVG schematic: wells → tank → pump → pipeline → disposal. No connections between systems are inferred.

Zoom navigation follows the user's focus: at map zoom 11, a nearby system opens; at zoom 15, a nearby facility opens. The target must be within 80 screen pixels of the focus. Zooming the facility below 65% of its initial fitted scale returns to its system at zoom 14; zooming the system out to level 9 returns to the network. Parent camera positions are retained. Markers, breadcrumbs, keyboard controls, and the locations list provide explicit alternatives.

If tiles cannot load, the app retains markers, connections, labels, and the distance scale on a plain background. The **Street map** toggle also enables this view. Online tiles require internet access; locally bundled Leaflet does not make the street imagery available offline. OpenStreetMap attribution remains visible; see its [copyright and attribution information](https://www.openstreetmap.org/copyright) and [tile usage policy](https://operations.osmfoundation.org/policies/tiles/). Leaflet's license is included in `Java Webserver/websiteFiles/vendor/leaflet/LICENSE`.

## Architecture

The app is a single Java process serving both static frontend assets and a JSON API. The frontend uses plain HTML, CSS, JavaScript, Leaflet, and SVG. There is no frontend build pipeline, external database, or separate application server to configure.

```text
Browser: HTML / CSS / JavaScript
  ├─ Leaflet geographic views ← OpenStreetMap street tiles
  ├─ SVG equipment schematic
  └─ Event list, inspector, filters, and export
                    │ same-origin JSON API
Java 17 HTTP server
  ├─ WebToolkit: request validation, JSON responses, static assets
  └─ DatasetService: isolated analysis and published snapshot
       ├─ GitHubSource → challenge repository at a pinned commit
       └─ EventAnalyzer → detected events, countdowns, response plans
```

| Component | Responsibility |
| --- | --- |
| `src/WebServer/WebServer.java` | Starts the localhost server and registers API/static routes. |
| `src/WebServer/GitHubSource.java` | Discovers dataset folders, resolves source commits, downloads inputs, and handles discovery caching and rate limits. |
| `src/WebServer/DatasetService.java` | Validates selection, isolates each analysis, publishes complete snapshots, and rejects stale dataset IDs. |
| `src/WebServer/EventAnalyzer.java` | Validates telemetry and computes incidents, countdowns, and advisory plans. |
| `src/WebServer/Event.java` / `EventSort.java` | Defines event output and deterministic sorting. |
| `src/WebToolkit/` | Handles HTTP methods, queries, structured errors, and byte-safe static responses, including the SVG logo. |
| `websiteFiles/js/app.js` | Owns frontend state, data loading, demo fixtures, event interaction, SVG schematic, and exports. |
| `websiteFiles/js/geography.js` | Owns geographic layers, map cameras, tile fallback, and map zoom navigation. |
| `websiteFiles/vendor/leaflet/` | Contains the pinned local map library and its license. |

Paths in this table are relative to `Java Webserver/`. JSON support is included under `src/org/json/`.

## Dataset and analysis flow

The live source is [`nihaal419/HackUTD-ZeroDay`](https://github.com/nihaal419/HackUTD-ZeroDay), on its `main` branch. A dataset folder must contain all three files:

| File | Role |
| --- | --- |
| `network.json` | Asset hierarchy, IDs, coordinates, capacities, and equipment configuration. |
| `ops.json` | Operational assumptions such as fleet availability, travel times, repair durations, and costs. |
| `telemetry.csv` | Timestamped sensor observations used for detection and countdown calculations. |

1. The frontend asks the server for available folders. The server shares a 60-second discovery cache and handles truncated repository-tree listings explicitly.
2. Selecting a dataset sends its folder URL to the backend. The backend resolves a commit before downloading the three inputs, keeping configuration and telemetry on the same revision.
3. Each request validates and analyzes its inputs in isolated temporary storage. The checked-in `telemetry.csv` is not overwritten.
4. Only a successful, complete analysis publishes its network and events together. A valid recording with zero incidents is a successful result. Failed loads preserve the previous analysis; an older competing request cannot overwrite a newer selection.
5. The response includes a unique `dataset_id`, source revision, and analysis timestamps derived from the telemetry. Network, sorting, and export requests must carry that ID. A replaced ID returns HTTP 409 instead of mixing recordings.

The server keeps one current published analysis in memory. Restarting clears it. Dataset discovery never automatically switches that analysis. Neither dataset prose nor repository documentation is treated as executable instructions; the live downloader only requests the three input files, not answer keys.

## API

| Method and route | Input | Result |
| --- | --- | --- |
| `GET /api/datasets` | None | Discovered dataset folders. |
| `POST /api/getevents` | JSON: `{"github-repo":"https://github.com/nihaal419/HackUTD-ZeroDay/tree/main/<folder>"}` | Computes and publishes an analysis; returns events and metadata. |
| `GET /api/getnetwork` | Query: `dataset_id` | Network for the matching analysis. |
| `GET /api/resortevents` | Query: `dataset_id`, `pass-params=priority\|recent\|cost` | Complete event collection in the requested order. |
| `GET /api/exportevents` | Query: `dataset_id` | Selected analysis events and metadata as JSON. |

Priority sorts ongoing pump failures by shortest countdown, then highest lost revenue per hour; other ongoing incidents follow, then resolved history. Recent sorts newest onset first. Cost sorts highest estimated cost first, with unavailable costs last. Event ID is the final tie-breaker. Exports include the complete collection, regardless of visible filters; demo export downloads the built-in events.

## Limitations

Detection uses explicit fault states and heuristic flow/pressure thresholds. Countdown uses observed inflow and available tank capacity. The planner searches a bounded set of truck and well-shut-in actions under shared fleet constraints; it does not explore every possible dispatch schedule. Confidence values are evidence-strength scores rather than calibrated probabilities.

The app analyzes a selected recording; its 60-second check discovers dataset folders rather than streaming fresh sensor samples. Street tiles depend on a third-party service, while GitHub access and rate limits affect live input loading. Missing coordinates and unavailable equipment locations remain explicit. See [the analysis documentation](Java%20Webserver/docs/event-analysis.md) for thresholds, validation rules, search bounds, and planning assumptions.

## Verification

From `Java Webserver`, compile and run the dependency-free Java integration suite:

```sh
javac --release 17 -sourcepath src -d bin src/WebServer/WebServer.java tests/WebServer/IntegrationTest.java
java -cp bin WebServer.IntegrationTest
```

Developers with Node installed can also run the map behavior tests:

```sh
node tests/geography.test.cjs
```

The Java tests use synthetic, unfamiliar asset IDs and controlled source responses. They cover real calculations, zero-event datasets, changing telemetry, atomic publication, failed downloads, sorting, export, HTTP errors, logo bytes, recursive discovery, cache behavior, and rate-limit backoff. They never overwrite the checked-in CSV or fetch answer keys.

See `Java Webserver/docs/event-analysis.md` for the current detector and planner assumptions. These remain heuristic and bounded; the app does not claim globally optimal plans or calibrated confidence scores.
