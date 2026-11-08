package WebServer;

import java.io.IOException;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;

/** Sensor-only incident detection and bounded, deterministic response planning.
 * No answer keys, wall-clock time, network requests, or dataset IDs are used.
 */
public final class EventAnalyzer
{
	// Heuristic noise margins, not calibrated probabilities. See docs/event-analysis.md.
	private static final double DEGRADED_FLOW = 0.90, RECOVERED_FLOW = 0.95;
	private static final double LEAK_LOSS = 0.07, RECOVERED_LOSS = 0.03;
	private static final int CONFIRM_SAMPLES = 3;
	private static final long SAMPLE_SECONDS = 300;
	private static final double VOLUME_MARGIN = 0.01, EPS = 1e-8;
	private static final int INFLOW = 0, LEVEL = 1, STATUS = 2, FLOW = 3,
			DISCHARGE = 4, RECEIVED = 5, PRESSURE = 6;
	private final JSONObject network, ops;

	public EventAnalyzer(JSONObject network, JSONObject ops)
	{
		if(network == null || ops == null)
			throw new IllegalArgumentException("Load network.json and ops.json before analyzing events");
		this.network = network;
		this.ops = ops;
	}

	/** Returns an empty array for a valid recording with no detected incidents.
	 * Invalid/missing inputs throw; WebServer.getEvents translates those failures to null.
	 */
	public Event[] analyze(Path telemetry) throws IOException
	{
		List<Facility> facilities = readNetwork();
		readTelemetry(telemetry, facilities);
		List<Event> events = new ArrayList<>();
		Map<String, List<FailurePlan>> pending = new LinkedHashMap<>();
		for(Facility facility : facilities)
		{
			detect(facility, events);
			for(Event event : facility.events)
				if("ongoing".equals(event.status) && "pump_failure".equals(event.category))
					pending.computeIfAbsent(facility.system, key -> new ArrayList<>())
						.add(planFailure(facility, event));
		}
		for(List<FailurePlan> failures : pending.values())
			allocateFleet(failures);
		EventSort sorting = Event.sort; // One ordering for this result, even if another request changes it.
		events.sort((a, b) -> {
			int result = sorting.compare(a, b);
			return result == 0 ? a.eventID.compareTo(b.eventID) : result;
		});
		return events.toArray(new Event[0]);
	}

	private List<Facility> readNetwork()
	{
		double shutdown = positive(network.getJSONObject("defaults")
				.getJSONObject("tank_limits_pct"), "shutdown");
		if(shutdown > 100)
			throw new IllegalArgumentException("Tank shutdown percentage must be at most 100");
		List<Facility> facilities = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		JSONArray systems = network.getJSONArray("systems");
		for(int i = 0; i < systems.length(); i++)
		{
			JSONObject system = systems.getJSONObject(i);
			String systemID = id(system, ids);
			double disposalPressure = nonnegative(system.getJSONObject("disposal_site"), "pressure_psi");
			JSONArray members = system.getJSONArray("facilities");
			for(int j = 0; j < members.length(); j++)
			{
				JSONObject member = members.getJSONObject(j);
				Facility f = new Facility();
				f.system = systemID;
				f.id = id(member, ids);
				JSONObject tank = member.getJSONObject("tank"), pump = member.getJSONObject("pump");
				f.tank = id(tank, ids);
				f.pump = id(pump, ids);
				f.pipeline = id(member.getJSONObject("pipeline"), ids);
				f.capacity = positive(tank, "capacity_bbl");
				f.maxFlow = positive(pump, "max_flow_bpd");
				f.shutdown = shutdown;
				f.disposalPressure = disposalPressure;
				f.wells = member.getJSONArray("wells");
				if(f.wells.length() > 20)
					throw new IllegalArgumentException("Well-subset planner supports at most 20 wells per facility");
				for(int k = 0; k < f.wells.length(); k++)
					id(f.wells.getJSONObject(k), ids);
				facilities.add(f);
			}
		}
		if(facilities.isEmpty())
			throw new IllegalArgumentException("Network contains no facilities");
		return facilities;
	}

	private void readTelemetry(Path path, List<Facility> facilities) throws IOException
	{
		Map<String, Sensor> sensors = new HashMap<>();
		for(Facility f : facilities)
		{
			sensors.put(f.id + ":inflow_bpd", new Sensor(f, INFLOW));
			sensors.put(f.tank + ":level_pct", new Sensor(f, LEVEL));
			sensors.put(f.pump + ":status", new Sensor(f, STATUS));
			sensors.put(f.pump + ":flow_bpd", new Sensor(f, FLOW));
			sensors.put(f.pump + ":discharge_psi", new Sensor(f, DISCHARGE));
			sensors.put(f.pipeline + ":receive_flow_bpd", new Sensor(f, RECEIVED));
			sensors.put(f.pipeline + ":pressure_psi", new Sensor(f, PRESSURE));
		}
		try(PushbackReader reader = new PushbackReader(Files.newBufferedReader(path, StandardCharsets.UTF_8), 1))
		{
			int first = reader.read();
			if(first != -1 && first != '\uFEFF') reader.unread(first);
			List<String> header = csvRow(reader);
			if(header == null)
				throw new IOException("telemetry.csv is empty");
			header.set(0, header.get(0).replace("\uFEFF", ""));
			Map<String, Integer> columns = new HashMap<>();
			for(int i = 0; i < header.size(); i++)
				if(columns.put(header.get(i).trim(), i) != null)
					throw new IOException("Duplicate CSV header");
			for(String name : List.of("timestamp", "ts_unix", "asset_id", "metric", "value"))
				if(!columns.containsKey(name))
					throw new IOException("Missing telemetry column: " + name);
			List<String> row;
			long line = 1;
			while((row = csvRow(reader)) != null)
			{
				line++;
				if(row.size() == 1 && row.get(0).isBlank())
					continue;
				if(row.size() != header.size())
					throw new IOException("Wrong column count at CSV record " + line);
				try
				{
					long time = Long.parseLong(row.get(columns.get("ts_unix")).trim());
					if(Instant.parse(row.get(columns.get("timestamp")).trim()).getEpochSecond() != time)
						throw new IllegalArgumentException("timestamp and ts_unix disagree");
					double value = Double.parseDouble(row.get(columns.get("value")).trim());
					if(!Double.isFinite(value))
						throw new IllegalArgumentException("Non-finite reading");
					String key = row.get(columns.get("asset_id")).trim() + ":" + row.get(columns.get("metric")).trim();
					Sensor sensor = sensors.get(key);
					if(sensor == null) // Well sensors and unrelated extra metrics are not needed by detection.
						continue;
					if((sensor.index == LEVEL && (value < 0 || value > 100)) ||
						(sensor.index == STATUS && value != 0 && value != 1 && value != 2))
						throw new IllegalArgumentException("Invalid level or pump status");
					Sample sample = sensor.facility.samples.computeIfAbsent(time, Sample::new);
					if(!Double.isNaN(sample.values[sensor.index]))
						throw new IllegalArgumentException("Duplicate sensor reading: " + key);
					sample.values[sensor.index] = value;
				}
				catch(IllegalArgumentException | DateTimeParseException exception)
				{
					throw new IOException("Invalid CSV record " + line + ": " + exception.getMessage(), exception);
				}
			}
		}
		Set<Long> times = facilities.get(0).samples.keySet();
		if(times.isEmpty())
			throw new IOException("No matching telemetry for the network");
		for(Facility f : facilities)
		{
			if(!f.samples.keySet().equals(times))
				throw new IOException("Missing facility samples: " + f.id);
			Long previous = null;
			for(Sample sample : f.samples.values())
			{
				for(double value : sample.values)
					if(Double.isNaN(value))
						throw new IOException("Incomplete telemetry for " + f.id + " at " + sample.time);
				if(previous != null && sample.time - previous != SAMPLE_SECONDS)
					throw new IOException("Telemetry must have contiguous five-minute samples");
				previous = sample.time;
			}
		}
	}

	/** RFC-style quoted fields, doubled quotes, BOM headers, LF and CRLF; no CSV dependency. */
	private static List<String> csvRow(PushbackReader reader) throws IOException
	{
		List<String> fields = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false, closed = false, seen = false;
		int c;
		while((c = reader.read()) != -1)
		{
			seen = true;
			if(quoted)
			{
				if(c == '"')
				{
					int next = reader.read();
					if(next == '"') field.append('"');
					else { quoted = false; closed = true; if(next != -1) reader.unread(next); }
				}
				else field.append((char)c);
			}
			else if(c == ',' || c == '\n' || c == '\r')
			{
				fields.add(field.toString());
				field.setLength(0);
				closed = false;
				if(c != ',')
				{
					if(c == '\r') { int next = reader.read(); if(next != '\n' && next != -1) reader.unread(next); }
					return fields;
				}
			}
			else if(c == '"' && field.length() == 0 && !closed) quoted = true;
			else if(closed || c == '"') throw new IOException("Invalid CSV quoting");
			else field.append((char)c);
		}
		if(quoted) throw new IOException("Unterminated CSV quote");
		if(!seen) return null;
		fields.add(field.toString());
		return fields;
	}

	private void detect(Facility f, List<Event> output)
	{
		Episode failure = new Episode(f, "pump_failure", f.pump, 1);
		Episode degradation = new Episode(f, "pump_degradation", f.pump, CONFIRM_SAMPLES);
		Episode leak = new Episode(f, "pipeline_leak", f.pipeline, CONFIRM_SAMPLES);
		for(Sample s : f.samples.values())
		{
			boolean fault = s.get(STATUS) == 2;
			failure.observe(s, fault ? 1 : -1);
			if(fault)
				degradation.close(s.time);
			else if(s.get(STATUS) == 1)
			{
				double ratio = s.get(FLOW) / f.maxFlow;
				boolean bad = ratio < DEGRADED_FLOW && s.get(DISCHARGE) < f.disposalPressure + 130;
				boolean good = ratio >= RECOVERED_FLOW && s.get(DISCHARGE) >= f.disposalPressure + 135;
				degradation.observe(s, bad ? 1 : good ? -1 : 0);
			}
			if(s.get(STATUS) == 1 && s.get(FLOW) > f.maxFlow * 0.1)
			{
				double loss = (s.get(FLOW) - s.get(RECEIVED)) / s.get(FLOW);
				boolean bad = loss > LEAK_LOSS && s.get(PRESSURE) < f.disposalPressure + 30;
				boolean good = loss < RECOVERED_LOSS && s.get(PRESSURE) >= f.disposalPressure + 35;
				leak.observe(s, bad ? 1 : good ? -1 : 0);
			}
			// Stopped/failed pumps cannot establish pipeline recovery or degradation recovery.
		}
		Sample latest = f.samples.lastEntry().getValue();
		for(Event event : f.events)
		{
			Sample levelSample = event.endUnix == null ? latest : f.samples.get(event.endUnix);
			event.levelPCT = levelSample.get(LEVEL);
			if(!"pump_failure".equals(event.category) && "ongoing".equals(event.status))
				event.recommendedActions.put(new JSONObject().put("type", "field_inspection").put("asset_id", event.assetID));
		}
		output.addAll(f.events);
	}

	private static final class Episode
	{
		final Facility facility;
		final String category, asset;
		final int confirmations;
		Event active;
		long candidateTime, recoveryTime;
		int badCount, goodCount;
		Episode(Facility facility, String category, String asset, int confirmations)
		{
			this.facility = facility; this.category = category; this.asset = asset; this.confirmations = confirmations;
		}
		void observe(Sample sample, int signal)
		{
			if(signal > 0)
			{
				goodCount = 0;
				if(badCount++ == 0) candidateTime = sample.time;
				if(active == null && badCount >= confirmations)
				{
					active = new Event();
					active.systemID = facility.system; active.facilityID = facility.id; active.assetID = asset;
					active.category = category; active.status = "ongoing";
					active.onsetUnix = candidateTime; active.onsetTime = Instant.ofEpochSecond(candidateTime).toString();
					active.eventID = "EV-" + UUID.nameUUIDFromBytes((asset + ":" + category + ":" + candidateTime)
							.getBytes(StandardCharsets.UTF_8));
					active.confidence = confirmations == 1 ? 0.99 : 0.90;
					active.evidence = switch(category) {
						case "pump_failure" -> "Pump status reports fault (2).";
						case "pump_degradation" -> "At least three running samples show flow below 90% of rated capacity and reduced discharge pressure.";
						default -> "At least three running samples show pipeline flow loss above 7% and reduced receiving pressure.";
					};
					facility.events.add(active);
				}
			}
			else if(signal < 0)
			{
				badCount = 0;
				if(goodCount++ == 0) recoveryTime = sample.time;
				if(active != null && goodCount >= confirmations) close(recoveryTime);
			}
			else { badCount = 0; goodCount = 0; }
		}
		void close(long time)
		{
			if(active != null)
			{
				active.endUnix = time; active.endTime = Instant.ofEpochSecond(time).toString(); active.status = "resolved";
				active = null;
			}
			badCount = 0; goodCount = 0;
		}
	}

	private FailurePlan planFailure(Facility f, Event event)
	{
		JSONObject prices = ops.getJSONObject("prices");
		double oil = nonnegative(prices, "oil_per_bbl"), gas = nonnegative(prices, "gas_per_mcf");
		double repair = positive(ops.getJSONObject("expected_repair_hours"), "pump_failure");
		JSONObject systemOps = ops.getJSONObject("systems").getJSONObject(f.system);
		double fleetNumber = nonnegative(systemOps, "emergency_fleet_size");
		if(fleetNumber != Math.rint(fleetNumber) || fleetNumber > 100)
			throw new IllegalArgumentException("Fleet size must be an integer between 0 and 100");
		int fleet = (int)fleetNumber;
		Sample now = f.samples.lastEntry().getValue();
		double horizon = repair - (now.time - event.onsetUnix) / 3600.0;
		double rate = recentInflow(f) / 24.0;
		double volume = now.get(LEVEL) / 100.0 * f.capacity;
		double limit = f.shutdown / 100.0 * f.capacity;
		double room = Math.max(0, limit - volume);
		if(room <= VOLUME_MARGIN) event.hoursToLimit = 0.0;
		else if(rate > EPS) event.hoursToLimit = room / rate;
		else event.hoursToLimit = null;
		List<Well> wells = new ArrayList<>();
		double baseline = 0, revenue = 0;
		for(int i = 0; i < f.wells.length(); i++)
		{
			JSONObject well = f.wells.getJSONObject(i);
			double water = nonnegative(well, "baseline_water_bpd") / 24.0;
			double value = (nonnegative(well, "oil_bpd") * oil + nonnegative(well, "gas_mcfd") * gas) / 24.0;
			wells.add(new Well(well.getString("id"), water, value));
			baseline += water; revenue += value;
		}
		Plan[] choices = new Plan[fleet + 1];
		FailurePlan result = new FailurePlan(event, choices, fleet);
		if(horizon <= 0)
		{
			event.evidence += " Expected repair time has elapsed but the fault persists; no repair horizon is available for cost planning.";
			choices[0] = new Plan(0, 0, new JSONArray().put(new JSONObject().put("type", "field_inspection").put("asset_id", f.pump)), false);
			return result;
		}
		boolean tripped = now.get(INFLOW) <= EPS && volume >= limit - f.capacity * 0.002;
		if(tripped)
		{
			event.hoursToLimit = 0.0;
			event.evidence += " Tank has reached its shutdown limit and inflow has stopped; wells stay shut until repair.";
			choices[0] = new Plan(0, revenue * horizon, none(), true);
			return result;
		}
		double automaticCost = event.hoursToLimit == null ? 0 : revenue * Math.max(0, horizon - event.hoursToLimit);
		choices[0] = new Plan(0, automaticCost, none(), true);
		// Scale baseline well shares to the recent observed aggregate inflow.
		if(baseline <= EPS && rate > EPS)
			throw new IllegalArgumentException("Positive inflow but no baseline well water at " + f.id);
		final double scale = baseline > EPS ? rate / baseline : 0;
		wells.replaceAll(w -> new Well(w.id, w.water * scale, w.revenue));
		JSONObject trucking = ops.getJSONObject("emergency_trucking");
		double capacity = positive(trucking, "truck_capacity_bbl"), tripCost = nonnegative(trucking, "cost_per_trip");
		JSONObject travel = systemOps.getJSONObject("facilities").getJSONObject(f.id);
		double response = nonnegative(travel, "response_min") / 60.0, cycle = positive(travel, "cycle_min") / 60.0;
		int maxTrips = Math.max(0, (int)Math.ceil((horizon - response) / cycle - EPS));
		if(maxTrips > 1000) throw new IllegalArgumentException("Repair horizon requires too many truck trips");
		for(int trucks = 0; trucks <= fleet; trucks++)
			for(int trips = trucks == 0 ? 0 : 1; trips <= (trucks == 0 ? 0 : maxTrips); trips++)
				for(int mask = 0; mask < (1 << wells.size()); mask++)
				{
					double savedRate = 0, lostRevenue = 0;
					JSONArray closed = new JSONArray();
					for(int i = 0; i < wells.size(); i++)
						if((mask & (1 << i)) != 0)
						{
							Well well = wells.get(i);
							savedRate += well.water; lostRevenue += well.revenue; closed.put(well.id);
						}
					double start = latestShutIn(volume, rate, savedRate, limit, horizon,
							trucks * capacity, trips, response, cycle);
					if(Double.isNaN(start)) continue;
					double cost = trucks * trips * tripCost + lostRevenue * (horizon - start);
					if(choices[trucks] != null && cost >= choices[trucks].cost - EPS) continue;
					JSONArray actions = new JSONArray();
					if(trucks > 0) actions.put(new JSONObject().put("type", "dispatch_truck").put("facility_id", f.id)
							.put("trucks", trucks).put("trips_per_truck", trips));
					if(closed.length() > 0 && start < horizon - EPS)
						actions.put(new JSONObject().put("type", "shut_in_wells").put("wells", closed).put("start_after_min", start * 60));
					choices[trucks] = new Plan(trucks, cost, actions.isEmpty() ? none() : actions, true);
				}
		event.evidence += String.format(Locale.ROOT, " Forecast uses %.1f bbl/hour inflow and %.2f hours remaining to expected repair.", rate, horizon);
		return result;
	}

	/** Linear volume balance between arrivals. Compute the latest common shut-in time,
	 * then verify every pre-trip peak, post-trip minimum, and the repair endpoint.
	 */
	private static double latestShutIn(double volume, double rate, double saved, double limit,
			double horizon, double load, int trips, double response, double cycle)
	{
		double start = horizon;
		for(int i = 0; i <= trips; i++)
		{
			double time = i == trips ? horizon : response + i * cycle;
			double excess = volume + rate * time - i * load - (limit - VOLUME_MARGIN);
			if(excess > 0)
			{
				if(saved <= EPS) return Double.NaN;
				start = Math.min(start, time - excess / saved);
			}
		}
		if(start < -EPS) return Double.NaN;
		start = Math.max(0, start);
		for(int i = 0; i <= trips; i++)
		{
			double time = i == trips ? horizon : response + i * cycle;
			double before = volume + rate * time - saved * Math.max(0, time - start) - i * load;
			if(before > limit - VOLUME_MARGIN + EPS || before < -EPS ||
				(i < trips && (time >= horizon || before < load - EPS))) return Double.NaN;
		}
		return start;
	}

	/** Minimum total cost over the enumerated plans with a shared fleet budget.
	 * Immediate dispatches reserve their trucks; no delayed dispatch or cross-job reuse.
	 */
	private static void allocateFleet(List<FailurePlan> failures)
	{
		int fleet = failures.get(0).fleet, count = failures.size();
		double[][] costs = new double[count + 1][fleet + 1];
		int[][] selected = new int[count + 1][fleet + 1];
		for(int i = 1; i <= count; i++)
			for(int available = 0; available <= fleet; available++)
			{
				costs[i][available] = Double.POSITIVE_INFINITY;
				for(int used = 0; used <= available; used++)
				{
					Plan plan = failures.get(i - 1).choices[used];
					if(plan != null && costs[i - 1][available - used] + plan.cost < costs[i][available])
					{
						costs[i][available] = costs[i - 1][available - used] + plan.cost;
						selected[i][available] = used;
					}
				}
			}
		int available = fleet;
		for(int i = count; i > 0; i--)
		{
			FailurePlan failure = failures.get(i - 1);
			int used = selected[i][available];
			Plan plan = failure.choices[used];
			failure.event.recommendedActions = plan.actions;
			failure.event.estimatedCost = plan.knownCost ? plan.cost : null;
			available -= used;
		}
	}

	private static double recentInflow(Facility f)
	{
		// Do not carry pre-shutdown inflow forward when the latest wells have stopped.
		if(f.samples.lastEntry().getValue().get(INFLOW) <= EPS) return 0;
		double[] recent = f.samples.descendingMap().values().stream().limit(7)
				.mapToDouble(s -> Math.max(0, s.get(INFLOW))).sorted().toArray();
		return recent[recent.length / 2];
	}
	private static JSONArray none() { return new JSONArray().put(new JSONObject().put("type", "none")); }
	private static String id(JSONObject object, Set<String> seen)
	{
		String id = object.getString("id");
		if(id.isBlank() || !seen.add(id)) throw new IllegalArgumentException("Empty or duplicate network ID: " + id);
		return id;
	}
	private static double nonnegative(JSONObject object, String name)
	{
		double value = object.getDouble(name);
		if(!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid " + name);
		return value;
	}
	private static double positive(JSONObject object, String name)
	{
		double value = nonnegative(object, name);
		if(value == 0) throw new IllegalArgumentException(name + " must be positive");
		return value;
	}
	private static final class Facility
	{
		String system, id, tank, pump, pipeline;
		double capacity, maxFlow, shutdown, disposalPressure;
		JSONArray wells;
		final TreeMap<Long, Sample> samples = new TreeMap<>();
		final List<Event> events = new ArrayList<>();
	}
	private static final class Sample
	{
		final long time;
		final double[] values = new double[7];
		Sample(long time) { this.time = time; Arrays.fill(values, Double.NaN); }
		double get(int index) { return values[index]; }
	}
	private record Sensor(Facility facility, int index) {}
	private record Well(String id, double water, double revenue) {}
	private record Plan(int trucks, double cost, JSONArray actions, boolean knownCost) {}
	private record FailurePlan(Event event, Plan[] choices, int fleet) {}
}
