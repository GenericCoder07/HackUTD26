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
| Pump degradation | Sustained loss of running flow and discharge pressure relative to healthy baseline/noise | Sustained return of both signals to the healthy baseline |
| Pipeline leak | Sustained receiving-flow loss and receiving-pressure deficit relative to healthy baseline/noise | Sustained return of both signals to the healthy baseline |

The first 48 running samples provide a trimmed-mean baseline and median absolute
deviation noise estimate, provided their median is near the nominal physical
baseline. Otherwise the nominal baseline is used so an obvious initial fault is
not learned as healthy. Minimum noise estimates are 0.004 normalized flow and
2 psi. Nominal running pressure offsets are +150 psi at the pump and +50 psi at
the pipeline, relative to disposal pressure, per the dataset specification.

Both signals use seven-running-sample median windows. Flow shift must exceed
`max(0.008, 3 * flowNoise / sqrt(7))`; pressure deficit must exceed
`max(2 psi, 3 * pressureNoise / sqrt(7))` for degradation, or a 5 psi floor for
leaks. Three successive abnormal windows confirm the incident. Seven successive
windows below half those thresholds confirm recovery, preventing early weak
faults from fragmenting into multiple incidents as noise crosses a threshold.
These are heuristic detection thresholds, not statistical confidence intervals.

Confirmation time is exported as `detected_unix`, separately from onset.
Pump onset is estimated by fitting a healthy plateau followed by either a
continuous ramp or a step, with noise-normalized least squares over a five-minute
time grid. Leak onset is the first corroborated affected running observation;
extrapolating its later slope to zero would misplace an unknown initial leak step.
Recovery uses a fitted change from the established fault to a healthy baseline,
removing the confirmation window's delay from the reported end time.

Leak checks require pump flow above 10% of rated capacity. Off cycles neither
establish faults nor prove recovery. A pump failure closes an existing degradation
event and creates a separate failure event. Isolated spikes are suppressed by
the medians and repeated confirmation. The true start/end of a leak during an
off interval is not identifiable from these sensors: reported boundaries can
remain late until running evidence is available.

UUID-based event IDs use asset, category, and estimated onset time. Repeating the
same recording produces the same IDs; additional observations may refine onset
and therefore change a slow incident's ID across snapshots. A recording that
starts mid-fault can establish only an observed onset, not the true onset.
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

The event array must add `event.toJSON()` directly, without wrapping that object
in the Java-bean `new JSONObject(Object)` constructor.

## Verification

From the project root with a JDK on PATH:

```powershell
javac --release 17 -cp bin -sourcepath src -d tests/.classes tests/WebServer/EventDetectionTest.java src/WebServer/EventAnalyzer.java src/WebServer/Event.java
java -cp "tests/.classes;bin" WebServer.EventDetectionTest
# Compare a recording to an externally supplied key at a specified cutoff:
java -cp "tests/.classes;bin" WebServer.EventDetectionTest telemetry.csv "path/to/answer-key.json" "2026-10-08T12:00:00Z"
```

The new dependency-free regression suite uses five random noise seeds to check
normal cycling, isolated spikes, mild ramps, small leaks, repaired and repeated
step faults, faults already present at recording start, degradation transitioning
to failure, truncated recordings, 2040 timestamps, and inferred versus confirmed
onset. Its configuration fixture is public practice network/operations metadata;
no answer key is bundled or loaded by production code. Temporary CSV fixtures
and optional cutoff copies are deleted; the working recording is never rewritten.

Against the supplied October 8 12:00 UTC answer key, the revised detector returns
13/13 matching incidents with no extras and correct statuses, versus 10/13 before.
All five pump failure onsets remain exact. Estimated degradation onsets are within
15 minutes of the key; leak onset differences are +60, -5, and +10 minutes.
EVENT_1 still ends at 10:00 rather than the hidden 08:00 repair time: the pump was
off at repair and the first observable recovered running sample is at 10:00.
Other matched resolved end times are exact. These are snapshot-specific evaluation
results, not a guarantee of performance on unseen recordings.
