package WebServer;

public class Event
{
	public static EventSort sort = EventSort.RECENT;
	
	String eventID, systemID, facilityID, assetID, category, status, onsetTime, endTime, evidence;
	int onsetUnix, endUnix;
	double confidence;
	
	
}
