package WebServer;

import java.util.function.BiFunction;

public enum EventSort
{
	RECENT((A, B) -> {
		return Integer.compare(B.onsetUnix, A.onsetUnix);
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
