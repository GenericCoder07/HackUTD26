# Website files

Place website assets in the matching folders: `html` for pages, `css` for stylesheets, `js` for scripts, and `images` for images.

## Beat the Shutdown

This is a plain HTML/CSS/JavaScript recording-analysis console. There is no build step, operational control, runtime demo data, or answer-key request. The Java server's existing static route declarations map `/` to `html/index.html`, `/css/style.css` to the stylesheet, and `/js/app.js` to the deferred script. Start the server with `Java Webserver` as its working directory once its backend compilation issues are resolved.

All branding tokens are in the first block of `css/style.css`. Dataset repository configuration is at the top of `js/app.js`. Dataset choices are discovered through GitHub's public Contents API; metadata failures offer an explicit manual folder entry. No credentials are embedded.

## Backend handoff

The `API` adapter in `js/app.js` is the single integration point. These contracts remain provisional:

| Operation | Request | Expected response |
| --- | --- | --- |
| Analyze | `POST /api/getevents`, JSON `{"github-repo":"<exact dataset folder web URL>"}` | Detailed event array or `{events: [...]}` |
| Network | `GET /api/getnetwork`, after analysis completes | Network object with `systems`, or `{network: ...}` |
| Sort | `GET /api/resortevents?pass-params=priority`, `recent`, or `cost` | Complete detailed event collection in authoritative backend order |
| Export | `GET /api/exportevents` | JSON URL string or `{url: "..."}` |

The adapter accepts `success` and numeric `response` metadata. Analysis envelopes can explicitly supply `dataset_id` and `as_of_unix` / `as_of_ts`. Dataset ID and exact folder URL are kept in a context passed to each adapter operation, ready for a future documented contract. They are not sent on GET requests under the current assumptions. Analysis endpoint time is never inferred from incidents or folder names.

Confirm that `est_cost` is the total event-plan implementation cost in USD. Missing/incompatible cost, confidence, level, and countdown values remain unavailable; zero is retained. The sort endpoint must return all statuses, with recent onset descending and cost descending if those intended contract directions are adopted. The browser only filters status/scope; it does not recompute backend priority or costs.

Dataset analyses are serialized, including their subsequent network/sort reads. The latest pending selection wins; old responses cannot replace current results. Sorting has its own generation guard. Stale retained results display their original folder label and cannot be exported during a failed or pending dataset switch.

## Verification and current limitation

Verified with Chromium against a temporary static HTTP server and intercepted development responses kept outside production files: exact dataset POST URLs, dynamic dataset discovery/refresh, event wrappers and malformed responses, null versus zero, mixed recommendations, resolved history, linked three-level topology selection, backend sort order and failed-sort preservation, serialized rapid selections, export protocol validation, zoom controls, and desktop/mobile overflow. No fixture fallback is shipped in this application.

The checked-in Java API code is incomplete: it contains syntax/type errors, placeholder handlers returning null, a GET/body parsing mismatch, and no export route. This frontend does not modify those teammate-owned files. End-to-end Java/API integration remains unverified until those handlers are completed. Static routes were inspected; browser verification used a separate static server, not a running Java application.
