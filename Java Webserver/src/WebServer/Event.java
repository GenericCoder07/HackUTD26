package WebServer;

import org.json.JSONObject;
import org.json.JSONArray;

public class Event implements Comparable<Event>
{
	public static EventSort sort = EventSort.RECENT;
	
	String eventID, systemID, facilityID, assetID, category, status, onsetTime, endTime, evidence;
	long onsetUnix;
	long detectedUnix;
	Long endUnix;
	double levelPCT;
	double confidence;
	Double hoursToLimit, estimatedCost;
	JSONArray recommendedActions = new JSONArray();
	
	public Event()
	{
		
	}
	
	public Event(JSONObject event)
	{
		eventID = event.getString("event_id");
		systemID = event.getString("system_id");
		facilityID = event.getString("facility_id");
		assetID = event.getString("asset_id");
		category = event.getString("category");
		status = event.getString("status");
		onsetTime = event.getString("onset_ts");
		endTime = event.isNull("end_ts") ? null : event.getString("end_ts");
		Object evidenceValue = event.get("evidence");
		evidence = evidenceValue instanceof JSONArray ? ((JSONArray)evidenceValue).optString(0, "") : evidenceValue.toString();
		
		onsetUnix = event.getLong("onset_unix");
		detectedUnix = event.getLong("detected_unix");
		endUnix = event.isNull("end_unix") ? null : event.getLong("end_unix");
		
		levelPCT = event.getDouble("level_pct");
		confidence = event.getDouble("confidence");
		Object hours = event.opt("hours_to_limit");
		if(hours == null || hours == JSONObject.NULL)
			hoursToLimit = null;
		else if(hours instanceof JSONObject)
			hoursToLimit = ((JSONObject)hours).optDoubleObject("p50", null);
		else
			hoursToLimit = event.getDouble("hours_to_limit");
		estimatedCost = event.isNull("est_cost") ? null : event.getDouble("est_cost");
		
		recommendedActions = event.getJSONArray("recommended_actions");
	}
	
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
		event.put("detected_unix", detectedUnix);
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
