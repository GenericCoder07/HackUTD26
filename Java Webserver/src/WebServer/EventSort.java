package WebServer;

import java.util.function.BiFunction;

public enum EventSort
{
	RECENT((A, B) -> {
		return Long.compare(B.onsetUnix, A.onsetUnix);
	}),
	COST((A, B) -> {
		return A.estimatedCost != null && B.estimatedCost != null ? Double.compare(B.estimatedCost, A.estimatedCost) : 0;
	}),
	PRIORITY((A, B) -> {
		return A.hoursToLimit != null && B.hoursToLimit != null ? Double.compare(A.hoursToLimit, B.hoursToLimit) : 0;
	});
	
	private BiFunction<Event, Event, Integer> func;
	private EventSort(BiFunction<Event, Event, Integer> func)
	{
		this.func = func;
	}
	
	public int compare(Event a, Event b)
	{
		return func.apply(a, b);
	}
}
