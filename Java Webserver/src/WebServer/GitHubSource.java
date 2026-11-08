package WebServer;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fetches only data files. Documentation and answer keys are never downloaded. */
public final class GitHubSource implements DatasetService.Source {
    private static final String API = "https://api.github.com/repos/nihaal419/HackUTD-ZeroDay";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    record Reply(int status, byte[] body, java.net.http.HttpHeaders headers) {}
    @FunctionalInterface interface Transport { Reply get(HttpRequest request) throws IOException, InterruptedException; }
    private final Transport transport;
    private final java.util.function.LongSupplier clock;
    public GitHubSource() {
        clock = System::currentTimeMillis;
        transport = request -> { var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray()); return new Reply(response.statusCode(), response.body(), response.headers()); };
    }
    GitHubSource(Transport transport, java.util.function.LongSupplier clock) { this.transport = transport; this.clock = clock; }
    private JSONArray cached;
    private long checkedAt;
    private volatile long blockedUntil;
    private String cachedRevision;
    private byte[] get(String url) throws IOException, InterruptedException {
        if (clock.getAsLong() < blockedUntil)
            throw new DatasetService.Failure(429, "GitHub is rate limited. Retry after " + Instant.ofEpochMilli(blockedUntil));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90))
            .header("Accept", "application/vnd.github+json").header("User-Agent", "Watchpot-HackUTD26").GET().build();
        Reply response = transport.get(request);
        if (response.status() == 403 || response.status() == 429) {
            long until = clock.getAsLong() + 60_000;
            try { until = Math.max(until, clock.getAsLong() + Long.parseLong(response.headers().firstValue("retry-after").orElse("0")) * 1000); } catch (NumberFormatException ignored) {}
            if (response.headers().firstValue("x-ratelimit-remaining").orElse("").equals("0"))
                try { until = Math.max(until, Long.parseLong(response.headers().firstValue("x-ratelimit-reset").orElse("0")) * 1000); } catch (NumberFormatException ignored) {}
            blockedUntil = until;
            throw new DatasetService.Failure(429, "GitHub access is limited. Retry after " + Instant.ofEpochMilli(until));
        }
        if (response.status() < 200 || response.status() >= 300)
            throw new IOException("GitHub returned HTTP " + response.status() + ". Check that the folder contains all three data files.");
        return response.body();
    }
    private JSONObject json(String url) throws IOException, InterruptedException {
        return new JSONObject(new String(get(url), StandardCharsets.UTF_8));
    }
    public String revision() throws IOException, InterruptedException { return json(API + "/commits/main").getString("sha"); }
    public byte[] file(String revision, String folder, String name) throws IOException, InterruptedException {
        if (!Set.of("network.json", "ops.json", "telemetry.csv").contains(name)) throw new IllegalArgumentException("Unsupported input file");
        return get("https://raw.githubusercontent.com/nihaal419/HackUTD-ZeroDay/" + encode(revision) + "/" + encode(folder) + "/" + name);
    }
    private static String encode(String path) {
        return Arrays.stream(path.split("/", -1)).map(p -> URLEncoder.encode(p, StandardCharsets.UTF_8).replace("+", "%20")).reduce((a,b) -> a + "/" + b).orElse("");
    }
    /** Shared one-minute cache; an unchanged revision avoids downloading the tree again. */
    public synchronized JSONArray discover() throws IOException, InterruptedException {
        long now = clock.getAsLong();
        if (cached != null && now - checkedAt < 60_000) return new JSONArray(cached.toString());
        JSONObject commit = json(API + "/commits/main");
        String revision = commit.getString("sha");
        if (cached != null && revision.equals(cachedRevision)) { checkedAt = now; return new JSONArray(cached.toString()); }
        String tree = commit.getJSONObject("commit").getJSONObject("tree").getString("sha");
        JSONObject recursive = json(API + "/git/trees/" + tree + "?recursive=1");
        Map<String, Set<String>> folders = new TreeMap<>();
        if (!recursive.optBoolean("truncated")) collect(recursive.getJSONArray("tree"), "", folders);
        else walk(tree, "", folders, new int[]{0});
        JSONArray result = new JSONArray();
        for (var entry : folders.entrySet()) {
            String path = entry.getKey();
            if (!path.isEmpty() && entry.getValue().containsAll(Set.of("network.json", "ops.json", "telemetry.csv")))
                result.put(new JSONObject().put("path", path).put("url", "https://github.com/nihaal419/HackUTD-ZeroDay/tree/main/" + encode(path))
                    .put("group", path.equals("history") || path.startsWith("history/") ? "Practice" : "Judging"));
        }
        cached = result; cachedRevision = revision; checkedAt = now;
        return new JSONArray(result.toString());
    }
    static void collect(JSONArray entries, String prefix, Map<String, Set<String>> folders) {
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (!"blob".equals(entry.getString("type"))) continue;
            String path = prefix + entry.getString("path");
            int split = path.lastIndexOf('/');
            String folder = split < 0 ? "" : path.substring(0, split), name = path.substring(split + 1);
            folders.computeIfAbsent(folder, key -> new HashSet<>()).add(name);
        }
    }
    private void walk(String sha, String prefix, Map<String, Set<String>> folders, int[] requests) throws IOException, InterruptedException {
        if (++requests[0] > 500) throw new IOException("Repository tree is too large to enumerate safely. Enter a dataset folder manually.");
        JSONObject tree = json(API + "/git/trees/" + sha);
        if (tree.optBoolean("truncated")) throw new IOException("GitHub returned an incomplete directory. Enter a dataset folder manually.");
        JSONArray entries = tree.getJSONArray("tree");
        collect(entries, prefix, folders);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if ("tree".equals(entry.getString("type"))) walk(entry.getString("sha"), prefix + entry.getString("path") + "/", folders, requests);
        }
    }
}
