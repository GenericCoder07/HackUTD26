package WebServer;

import org.json.JSONObject;
import org.json.JSONArray;

public class Event implements Comparable<Event>
{
	public static EventSort sort = EventSort.RECENT;
	
	String eventID, systemID, facilityID, assetID, category, status, onsetTime, endTime, evidence;
	long onsetUnix;
	Long endUnix;
	double levelPCT;
	double confidence;
	Double hoursToLimit, estimatedCost;
	JSONArray recommendedActions = new JSONArray();
	
	
	public int compareTo(Event o)
	{
		return sort.compare(this, o);
	}
	
	public JSONObject toJSON()
	{
		JSONObject event = new JSONObject();
		event.put("event_id", eventID);
		event.put("system_id", systemID);
		event.put("facility_id", facilityID);
		event.put("asset_id", assetID);
		event.put("category", category);
		event.put("status", status);
		event.put("onset_ts", onsetTime);
		event.put("onset_unix", onsetUnix);
		event.put("end_ts", endTime == null ? JSONObject.NULL : endTime);
		event.put("end_unix", endUnix == null ? JSONObject.NULL : endUnix);
		event.put("confidence", confidence);
		event.put("evidence", new JSONArray().put(evidence));
		event.put("level_pct", levelPCT);
		event.put("hours_to_limit", hoursToLimit == null ? JSONObject.NULL : new JSONObject().put("p50", hoursToLimit));
		event.put("est_cost", estimatedCost == null ? JSONObject.NULL : estimatedCost);
		event.put("recommended_actions", recommendedActions);
		
		return event;
	}
}
