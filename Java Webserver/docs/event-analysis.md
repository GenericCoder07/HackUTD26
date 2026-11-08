# Event analysis

`WebServer.getEvents()` reads the currently loaded `networkJSON`, `opsJSON`, and
`telemetry.csv` in the Java process's working directory. It delegates to
`new EventAnalyzer(networkJSON, opsJSON).analyze(Path.of("telemetry.csv"))`.
The analyzer does not fetch data, modify the CSV, read an answer key, or use the
computer's clock. The final telemetry timestamp is the analysis time.

The method returns all detected ongoing and resolved incidents, ordered by the
existing `Event.sort` (currently most recent onset first). No incidents means an
empty array. Unloaded JSON, invalid configuration, unreadable files, and malformed
or incomplete telemetry cause `getEvents()` to log a diagnostic and return `null`.
The analyzer itself throws an exception so callers/tests can inspect the cause.

## Detection

The implementation follows the sensor schema in the project's
[dataset specification](https://github.com/nihaal419/HackUTD-ZeroDay/blob/main/SPEC.md).
IDs, capacities, rated flow, disposal pressure, prices, and travel times come from
the JSON files. No particular system, facility, or incident ID is hardcoded.

| Incident | Start evidence | Recovery evidence |
| --- | --- | --- |
| Pump failure | Pump status is `2` (fault); one sample is sufficient | First status other than `2` |
| Pump degradation | Three qualifying running samples with flow below 90% of rated flow AND discharge pressure below disposal pressure + 130 psi | Three qualifying running samples with flow at least 95% of rated flow AND discharge pressure at least disposal pressure + 135 psi |
| Pipeline leak | Three qualifying running samples losing more than 7% of pump flow AND receiving pressure below disposal pressure + 30 psi | Three qualifying running samples losing less than 3% of pump flow AND receiving pressure at least disposal pressure + 35 psi |

Leak checks require pump flow above 10% of rated capacity. Pump-off periods do
not provide leak/degradation evidence and preserve an existing episode and its
pending evidence. An indeterminate running sample resets confirmation counters.
Recovery is backdated to the first healthy sample in the confirmed recovery run;
onset is the first anomalous sample in the confirmed anomaly run. A pump failure
ends an existing degradation event and creates a separate failure event.
Small isolated spikes are ignored. These thresholds are heuristic: slight faults
below these margins and events beginning or ending while the pump is off may be
detected late or remain unresolved until observable running samples arrive.

Stable UUID-based event IDs use asset, category, and observed onset time. A recording
that starts mid-fault can establish only an observed onset, not the true onset.
Confidence is a fixed evidence-strength score (0.99 for explicit faults, 0.90 for
the confirmed flow/pressure rules), **not a calibrated probability**.

## Countdown and response plans

Ongoing pump failures use the median of the last seven inflow readings, divided
by 24 to convert barrels/day to barrels/hour. A latest zero inflow stays zero
rather than retaining earlier production. Countdown is remaining tank space
below the configured shutdown threshold divided by this flow. It is null when
there is no positive inflow, and zero for a tank already at shutdown. Resolved
incidents and other categories have no countdown. Only a point estimate (`p50`)
is supplied; no statistical uncertainty interval is invented.

The repair horizon is configured pump-failure repair duration minus elapsed time
since observed onset. If the expected repair is overdue but the fault persists,
the recommendation is inspection with unknown cost. An automatically shut
facility remains shut until repair; its plan is `none` and cost is remaining lost
revenue. Resolved events have no current recommendations or estimated plan costs.

For each other ongoing failure, the planner compares:

- No intervention, including revenue lost after automatic shutdown.
- Immediate truck dispatch with integer truck counts and trips per truck.
- Every subset of wells shut in at a common, possibly delayed time until repair.
- Combinations of those truck and well actions.

Well water contributions are baseline shares scaled to measured aggregate inflow;
revenue uses baseline oil/gas production and the configured prices. This assumes
the contributing wells follow the recording's normal production/automatic-shut-in
behavior; it does not infer independent manual well closures.

For each candidate the planner solves for the latest safe common well shut-in
time using piecewise linear tank-volume balance. It verifies all peaks immediately
before truck arrivals and at repair, and every post-trip minimum. Truck arrivals
must precede repair and have a full load available. A 0.01-barrel margin keeps
preventive plans strictly below the shutdown threshold. Cost is trip charges plus
lost revenue for selected wells. Fractional `start_after_min` values preserve the
computed timing; rounding them later could change feasibility.

Dynamic programming selects the minimum total cost among those candidates across
all ongoing failures in each system, subject to the shared fleet limit. Every
chosen truck is reserved from plan start. **This is not a global optimum over all
possible schedules**: delayed dispatch, reusing returned trucks on another job,
and separate closure times for individual selected wells are not explored. It
does not reconstruct historical intervention plans for resolved events.

The subset search is exponential in wells per facility; the current small-network
implementation rejects more than 20 wells, fleets above 100, or more than 1,000
trips per truck, rather than silently truncating the search. Practical inputs
should be much smaller (the practice network has three wells per facility).

## Input and output

CSV supports UTF-8/BOM, quoted fields, escaped quotes, CRLF/LF, rearranged columns,
and rows in any order. Timestamps must agree with `ts_unix`; required sensor values
must be finite, pump states valid, and tank levels within 0–100. Required facility,
tank, pump, and pipeline readings must form complete, matching five-minute frames.
Missing frames/metrics and duplicate readings are errors, not silently filled
values. Extra well/unknown sensor series are not used for detection.

`Event.toJSON()` now uses the frontend/spec's underscore keys, including
`event_id`, `onset_unix`, `level_pct`, `hours_to_limit`, `recommended_actions`, and
`est_cost`. End times and unavailable estimates are explicit JSON nulls. Evidence
is an array. Timestamps use `long`, and tank level retains fractional precision.
This replaces the previous hyphenated serialization keys.

This change implements the analysis method and model. The existing HTTP handlers
still need their response wiring: `/api/getevents` currently discards the returned
array and treats an empty result as failure; network, sort, and export handlers
remain separate integration work.

## Verification

From the project root with a JDK on PATH:

```powershell
javac --release 17 -cp bin -sourcepath src -d bin src/WebServer/WebServer.java src/WebServer/EventAnalyzer.java tests/WebServer/EventAnalyzerTest.java
java -cp bin WebServer.EventAnalyzerTest
```

The dependency-free tests cover cycling/noise, fault episodes, persistent slow
incidents, confirmed recovery, degradation transitioning to failure, stable IDs,
2040 timestamps, countdown units, costs, shared-fleet allocation, physical plan
replay, late trucks, insufficient water, already-shut tanks, overdue repairs,
quoted/unordered CSV, invalid inputs, and an unloaded server. Temporary fixtures
are removed; the real telemetry file is never overwritten. The real local
recording was also processed with its public network/operations configuration;
accuracy was not scored against an answer key.
