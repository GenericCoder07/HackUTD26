package WebServer;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Failed or superseded loads cannot replace a complete analysis. */
public final class DatasetService {
    public interface Source {
        String revision() throws IOException, InterruptedException;
        byte[] file(String revision, String folder, String name) throws IOException, InterruptedException;
        JSONArray discover() throws IOException, InterruptedException;
    }
    public static final class Failure extends RuntimeException {
        public final int status;
        public Failure(int status, String message) { super(message); this.status = status; }
    }
    private final Source source;
    private final AtomicLong generation = new AtomicLong();
    private volatile Snapshot current;
    private record Snapshot(String id, String path, String revision, String network,
                            Event[] events, long first, long last) {
        JSONObject metadata() {
            return new JSONObject().put("dataset_id", id).put("dataset_path", path)
                .put("source_revision", revision).put("start_unix", first)
                .put("as_of_unix", last).put("as_of_ts", Instant.ofEpochSecond(last).toString());
        }
        JSONObject result(EventSort sort) {
            Event[] ordered = events.clone();
            Arrays.sort(ordered, sort);
            JSONArray values = new JSONArray();
            for (Event event : ordered) values.put(event.toJSON());
            return metadata().put("events", values);
        }
    }
    public DatasetService(Source source) { this.source = source; }
    public JSONArray discover() throws IOException, InterruptedException { return source.discover(); }
    static String folder(String url) {
        URI uri;
        try { uri = URI.create(url); } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid dataset URL"); }
        String prefix = "/nihaal419/HackUTD-ZeroDay/tree/main/";
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())
            || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !uri.getPath().startsWith(prefix))
            throw new IllegalArgumentException("Choose a folder in nihaal419/HackUTD-ZeroDay on branch main");
        String path = uri.getPath().substring(prefix.length()).replaceAll("/+$", "");
        if (path.isBlank() || Arrays.stream(path.split("/", -1)).anyMatch(p -> p.isBlank() || p.equals(".") || p.equals("..") || p.contains("\\")))
            throw new IllegalArgumentException("Invalid dataset folder path");
        return path;
    }
    public JSONObject analyze(String url) throws IOException, InterruptedException {
        String path = folder(url);
        long ticket;
        synchronized (this) { ticket = generation.incrementAndGet(); }
        String revision = source.revision();
        Path directory = Files.createTempDirectory("watchpot-analysis-");
        Snapshot next;
        try {
            JSONObject network = new JSONObject(new String(source.file(revision, path, "network.json"), java.nio.charset.StandardCharsets.UTF_8));
            JSONObject ops = new JSONObject(new String(source.file(revision, path, "ops.json"), java.nio.charset.StandardCharsets.UTF_8));
            Path telemetry = directory.resolve("telemetry.csv");
            Files.write(telemetry, source.file(revision, path, "telemetry.csv"));
            EventAnalyzer analyzer = new EventAnalyzer(network, ops);
            Event[] events = analyzer.analyze(telemetry);
            next = new Snapshot(UUID.randomUUID().toString(), path, revision, network.toString(),
                events, analyzer.firstTimestamp(), analyzer.lastTimestamp());
        } finally {
            Files.deleteIfExists(directory.resolve("telemetry.csv"));
            Files.deleteIfExists(directory);
        }
        synchronized (this) {
            if (ticket != generation.get()) throw new Failure(409, "A newer analysis was requested. Select the dataset again.");
            current = next;
        }
        return next.result(EventSort.PRIORITY);
    }
    private Snapshot snapshot(String id) {
        Snapshot value = current;
        if (value == null) throw new Failure(409, "No analysis loaded. Select a dataset first.");
        if (id == null || !value.id.equals(id)) throw new Failure(409, "This analysis is stale. Select the dataset again.");
        return value;
    }
    public JSONObject network(String id) {
        Snapshot value = snapshot(id);
        return value.metadata().put("network", new JSONObject(value.network));
    }
    public JSONObject events(String id, String sort) { return snapshot(id).result(EventSort.parse(sort)); }
}
