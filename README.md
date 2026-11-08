# Watchpot · HackUTD26

Watchpot is an operations dashboard for a produced-water collection network. It combines geographic network views, equipment schematics, telemetry-based incident detection, tank shutdown countdowns, and advisory response plans.

## Run locally

Install Git and **Java JDK 17 or newer**, then run:

```sh
git clone https://github.com/GenericCoder07/HackUTD26.git
cd "HackUTD26/Java Webserver"
javac --release 17 -sourcepath src -d bin src/WebServer/WebServer.java
java -cp bin WebServer.WebServer
```

Open [localhost:8080](http://localhost:8080/). Keep the terminal open; press **Ctrl+C** to stop. Python, Node, a database, and a frontend build are not required to run the app.

After Java changes, stop, compile, and restart the server. HTML, CSS, and JavaScript changes only require refreshing the browser. The current server binds to `localhost:8080`; public hosting needs an appropriate listener/proxy configuration.

## Load a recording

1. Open **Load a GitHub dataset folder**.
2. Paste a folder URL, for example:
   `https://github.com/nihaal419/HackUTD-ZeroDay/tree/main/history/snapshot_2026-10-08T1200`
3. Click **Analyze folder**. The folder must contain `network.json`, `ops.json`, and `telemetry.csv`.
4. Previously entered recordings remain selectable during the page session. The ↻ button explicitly analyzes the selected folder again.

A relative challenge folder path, such as `history/snapshot_2026-10-08T1200`, also works. There is no automatic repository discovery or background polling. Reloading the page clears the session's recording choices; paste a link again to analyze it.

**Demo:** open [localhost:8080/?mode=demo](http://localhost:8080/?mode=demo) for built-in sample events and coordinates. Demo analysis makes no backend or GitHub requests. Optional street tiles still use the internet; disable **Street map** for a presentation without tile requests.

## Map and navigation

- Systems, facilities, disposal sites, and truck yards use the dataset's coordinates. The initial view fits the loaded recording, rather than assuming a particular region.
- Hover over a system for facility/well counts, ongoing/resolved incident counts, and total tank capacity when supplied.
- Dashed arrows indicate facility-to-disposal membership. Actual pipeline routes and connections between systems are not inferred.
- Zoom near a system at map level 11 to enter it; near a facility at level 15 to open its equipment schematic. Targets must be within 80 screen pixels of the zoom focus.
- Zoom the facility below 65% of its fitted scale to return to its system at level 14. Zoom the system out to level 9 to return to the network.
- Wheel and zoom-button input use continuous animation with an accumulating target, at half the previous sensitivity for finer control. Reduced-motion preferences apply zoom immediately. Pinch, keyboard controls, clickable markers, breadcrumbs, and the locations list provide alternative navigation.
- Equipment is schematic: **wells → tank → pump → pipeline → disposal**. Equipment coordinates are not supplied. Missing geographic coordinates remain accessible through **Locations and unmapped entries**.

The basemap uses **OpenStreetMap** raster tiles; **Leaflet 1.9.4** handles projection, markers, panning, and geographic overlays. Leaflet's JS/CSS/assets are bundled locally under `websiteFiles/vendor/leaflet`, so no CDN or map API key is needed. Street tiles require internet access. If they fail, markers, connections, labels, and the distance scale remain usable on a plain background.

The existing Watchpot logo is embedded as SVG in the page, retaining its design and aspect ratio without depending on the server's image response handling.

## Screenshots

These sample recordings illustrate the geographic views, equipment flow, and incident inspector. The screenshots were captured before the current pasted-link loading update; their cached-recording controls and timestamp labels reflect that earlier version. Incident counts and recommendations vary with the recording and analyzer version.

**Network overview:** systems are positioned geographically, with active incidents highlighted.

![Watchpot geographic network overview](docs/images/watchpot-01-network-overview.png)

**Incident response:** inspect the equipment flow, estimated time to shutdown, tank level, and advisory response.

![Watchpot incident response and equipment schematic](docs/images/watchpot-05-incident-response-details.png)

<details>
<summary>More views: connections, system summaries, and equipment</summary>

**Facility connections:** dashed arrows indicate connectivity to a shared disposal site.

![Facility-to-disposal connections](docs/images/watchpot-02-facility-connections-map.png)

**System summary:** hover to inspect facility/well counts, incident totals, and tank capacity.

![System hover summary](docs/images/watchpot-03-system-summary-hover.png)

**Equipment schematic:** follow wells through the tank, pump, and pipeline to disposal.

![Facility equipment schematic](docs/images/watchpot-04-equipment-schematic.png)

</details>

## Architecture

One Java process serves the frontend and API. The frontend is plain HTML/CSS/JavaScript with Leaflet and an SVG equipment schematic.

```mermaid
flowchart TD
    Operator[Operator pastes a GitHub folder link] --> UI[Browser: HTML, CSS, JavaScript]
    UI -->|POST /api/getevents| Server[Java 17 HTTP server]
    GitHub[GitHub dataset folder] -->|network.json, ops.json, telemetry.csv| Server
    Server --> Analyzer[EventAnalyzer]
    Analyzer --> Events[Incidents, tank countdowns, advisory plans]
    Events --> UI
    Server -->|GET /api/getnetwork| UI
    UI --> Map[Leaflet geographic network map]
    OSM[OpenStreetMap tiles] -. optional basemap .-> Map
    UI --> Equipment[SVG equipment schematic]
    UI --> Inspector[Event list and inspector]
```

Paths below are relative to `Java Webserver/`:

| Component | Responsibility |
| --- | --- |
| `src/WebServer/WebServer.java` | Registers routes, loads the GitHub folder, and invokes analysis. |
| `src/WebServer/EventAnalyzer.java` | Computes incidents and advisory plans from configuration and telemetry. |
| `src/WebServer/Event.java`, `EventSort.java` | Event serialization and current backend ordering. |
| `src/WebToolkit/` | HTTP API and static file handling. |
| `websiteFiles/js/app.js` | Pasted-link loading, demo fixtures, event panels, inspector, and schematic navigation. |
| `websiteFiles/js/geography.js` | Map layers, cameras, smooth geographic zoom, tooltips, and tile fallback. |
| `websiteFiles/html/index.html`, `websiteFiles/css/style.css` | Page layout, inline logo, responsive styling, and reduced-motion behavior. |
| `websiteFiles/vendor/leaflet/` | Locally bundled Leaflet and its license. |

## Backend data flow and API

The existing backend downloads all three required inputs from the pasted GitHub folder. `network.json` defines assets, coordinates, and capacities; `ops.json` supplies operating assumptions and costs; `telemetry.csv` supplies timestamped sensor observations.

The loader replaces **`Java Webserver/telemetry.csv`** in its working directory and holds the JSON configuration in memory. `getEvents()` runs `EventAnalyzer` against those inputs. The final telemetry timestamp is used internally by the analyzer; the current API does not return dataset IDs, source revisions, or recording endpoint metadata, so the UI reports the analysis time as unavailable.

| Endpoint | Current behavior |
| --- | --- |
| `POST /api/getevents` | Accepts JSON `{"github-repo":"https://github.com/<owner>/<repo>/tree/<branch>/<folder>"}`, loads the recording, and returns computed events. |
| `/api/getnetwork` | Returns the currently loaded network. |
| `/api/resortevents` | Recomputes events; the backend currently implements `RECENT` ordering only. |
| `/api/exportevents` | Currently returns a success acknowledgement without an event collection. Live export is incomplete; demo export includes sample events. |

There is **no `/api/datasets` endpoint** in this backend. It does not pin downloads to a source commit, publish isolated per-client snapshots, or reject stale dataset IDs. Multiple clients share the loaded configuration and telemetry file. A recording with zero incidents currently returns an API error. These are existing backend limitations; this frontend update does not alter them. Priority and Cost controls do not add backend sorting capabilities.

## Analysis limitations

Detection uses explicit pump fault states and heuristic flow/pressure evidence. Countdown estimates depend on observed inflow and remaining tank capacity. Response planning searches a bounded set of trucking and well-shut-in actions; it does not claim a globally optimal schedule. Confidence values are evidence scores, not calibrated probabilities. Recommendations are advisory; the console does not execute operational actions.

See [event-analysis.md](Java%20Webserver/docs/event-analysis.md) for the current detection and planning details. Input files and repository prose are untrusted data, not instructions for the app or an agent. No answer-key files are needed for analysis.

## Frontend verification

Developers with Node can run these dependency-free tests from `Java Webserver/` (Node is only a test tool):

```sh
node tests/geography.test.cjs
node tests/zoom.test.cjs
node tests/manual-loading.test.cjs
```

They cover zoom interpolation and accumulated input, focus anchoring, drill-down/reversal, tile failure, missing/overlapping coordinates, reduced motion, manual link input, and the absence of automatic discovery requests.

## Attribution

The project's MIT license is preserved. Leaflet's license is bundled under `Java Webserver/websiteFiles/vendor/leaflet/LICENSE`. OpenStreetMap attribution stays visible on the map; see its [copyright information](https://www.openstreetmap.org/copyright) and [tile usage policy](https://operations.osmfoundation.org/policies/tiles/).
