package WebServer;

import java.util.Comparator;
import java.util.Locale;

public enum EventSort implements Comparator<Event> {
    RECENT, PRIORITY, COST;

    public static EventSort parse(String value) {
        try { return valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Sort must be priority, recent, or cost"); }
    }
    public int compare(Event a, Event b) {
        int result = switch (this) {
            case RECENT -> Long.compare(b.onsetUnix, a.onsetUnix);
            case COST -> Comparator.nullsLast(Comparator.<Double>reverseOrder()).compare(a.estimatedCost, b.estimatedCost);
            case PRIORITY -> priority(a, b);
        };
        return result != 0 ? result : a.eventID.compareTo(b.eventID);
    }
    private static int rank(Event e) {
        return !"ongoing".equals(e.status) ? 2 : "pump_failure".equals(e.category) ? 0 : 1;
    }
    private static int priority(Event a, Event b) {
        int result = Integer.compare(rank(a), rank(b));
        if (result != 0 || rank(a) != 0) return result;
        result = Comparator.nullsLast(Comparator.<Double>naturalOrder()).compare(a.hoursToLimit, b.hoursToLimit);
        return result != 0 ? result : Double.compare(b.lostRevenuePerHour, a.lostRevenuePerHour);
    }
}
