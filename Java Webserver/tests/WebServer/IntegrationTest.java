package WebServer;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Run with Java 17; fixtures are generated locally and never fetch answer keys. */
public final class IntegrationTest {
    static int checks;
    static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    static final String PREFIX = "https://github.com/nihaal419/HackUTD-ZeroDay/tree/main/";
    static Map<String,byte[]> fixture(boolean fault, double level, String id) {
        JSONObject facility = new JSONObject().put("id", id + "_FAC").put("lat", 39.7).put("lon", -104.9)
            .put("tank",new JSONObject().put("id",id+"_TANK").put("capacity_bbl",1000))
            .put("pump",new JSONObject().put("id",id+"_PUMP").put("max_flow_bpd",1000))
            .put("pipeline",new JSONObject().put("id",id+"_PIPE"))
            .put("wells",new JSONArray().put(new JSONObject().put("id",id+"_WELL").put("baseline_water_bpd",1000).put("oil_bpd",100).put("gas_mcfd",50)));
        JSONObject network = new JSONObject().put("defaults",new JSONObject().put("tank_limits_pct",new JSONObject().put("shutdown",92)))
            .put("systems",new JSONArray().put(new JSONObject().put("id",id).put("center",new JSONArray(List.of(39.7,-104.9)))
            .put("disposal_site",new JSONObject().put("id",id+"_DISPOSAL").put("lat",39.8).put("lon",-104.8).put("pressure_psi",1000))
            .put("facilities",new JSONArray().put(facility))));
        JSONObject ops = new JSONObject().put("prices",new JSONObject().put("oil_per_bbl",60).put("gas_per_mcf",3))
            .put("expected_repair_hours",new JSONObject().put("pump_failure",12))
            .put("emergency_trucking",new JSONObject().put("truck_capacity_bbl",100).put("cost_per_trip",900))
            .put("systems",new JSONObject().put(id,new JSONObject().put("emergency_fleet_size",0).put("facilities",new JSONObject()
                .put(id+"_FAC",new JSONObject().put("response_min",60).put("cycle_min",90)))));
        StringBuilder csv = new StringBuilder("timestamp,ts_unix,asset_id,metric,value\n");
        for (int frame=0;frame<4;frame++) {
            long ts = 1_800_000_000L+300*frame;
            String[][] rows = {{"_FAC","inflow_bpd","1000"},{"_TANK","level_pct",String.valueOf(level)},
                {"_PUMP","status",fault?"2":"1"},{"_PUMP","flow_bpd",fault?"0":"1000"},
                {"_PUMP","discharge_psi",fault?"1000":"1150"},{"_PIPE","receive_flow_bpd",fault?"0":"1000"},{"_PIPE","pressure_psi",fault?"1000":"1050"}};
            for (String[] row:rows) csv.append(Instant.ofEpochSecond(ts)).append(',').append(ts).append(',').append(id).append(row[0]).append(',').append(row[1]).append(',').append(row[2]).append('\n');
        }
        return Map.of("network.json",network.toString().getBytes(StandardCharsets.UTF_8),"ops.json",ops.toString().getBytes(StandardCharsets.UTF_8),"telemetry.csv",csv.toString().getBytes(StandardCharsets.UTF_8));
    }
    static class Source implements DatasetService.Source {
        final Map<String,Map<String,byte[]>> folders = new ConcurrentHashMap<>();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        public String revision() { return "pinned-test-revision"; }
        public byte[] file(String revision,String folder,String name) throws IOException,InterruptedException {
            check(revision.equals("pinned-test-revision"),"same commit for every file");
            requests.add(name);
            if (folder.equals("slow") && name.equals("telemetry.csv")) { started.countDown(); release.await(10,TimeUnit.SECONDS); }
            if (!folders.containsKey(folder) || !folders.get(folder).containsKey(name)) throw new IOException("Missing data file");
            return folders.get(folder).get(name);
        }
        public JSONArray discover() {
            JSONArray values = new JSONArray();
            folders.keySet().stream().sorted().forEach(path -> values.put(new JSONObject().put("path",path).put("url",PREFIX+path).put("group","Judging")));
            return values;
        }
    }
    static void expectFailure(Runnable call,int status) {
        try { call.run(); throw new AssertionError("Expected failure"); }
        catch (DatasetService.Failure e) { check(e.status==status,"failure status"); }
    }
    static Event event(String id,String status,String category,Double hours,Double cost,double revenue,long onset) {
        Event e=new Event();e.eventID=id;e.status=status;e.category=category;e.hoursToLimit=hours;e.estimatedCost=cost;e.lostRevenuePerHour=revenue;e.onsetUnix=onset;return e;
    }
    static void githubTests() throws Exception {
        var now = new java.util.concurrent.atomic.AtomicLong(1_000_000);
        var version = new java.util.concurrent.atomic.AtomicInteger(1);
        List<String> calls = new ArrayList<>();
        GitHubSource source = new GitHubSource(request -> {
            String url = request.uri().toString(); calls.add(url);
            JSONObject body;
            if (url.endsWith("/commits/main")) body = new JSONObject().put("sha","rev"+version.get()).put("commit",new JSONObject().put("tree",new JSONObject().put("sha","tree"+version.get())));
            else if (url.contains("recursive=1")) body = new JSONObject().put("truncated",true).put("tree",new JSONArray());
            else if (url.endsWith("/git/trees/tree"+version.get())) body = new JSONObject().put("tree",new JSONArray().put(new JSONObject().put("type","tree").put("path","nested").put("sha","child")));
            else if (url.endsWith("/git/trees/child")) {
                JSONArray entries=new JSONArray();
                for (String name:List.of("network.json","ops.json","telemetry.csv")) entries.put(new JSONObject().put("type","blob").put("path",name));
                if (version.get()>1) entries.put(new JSONObject().put("type","tree").put("path","judge-added").put("sha","grandchild"));
                body=new JSONObject().put("tree",entries);
            } else if (url.endsWith("/git/trees/grandchild")) {
                JSONArray entries=new JSONArray();for(String name:List.of("network.json","ops.json","telemetry.csv"))entries.put(new JSONObject().put("type","blob").put("path",name));
                body=new JSONObject().put("tree",entries);
            } else throw new AssertionError("Unexpected GitHub request: "+url);
            return new GitHubSource.Reply(200,body.toString().getBytes(StandardCharsets.UTF_8),HttpHeaders.of(Map.of(),(a,b)->true));
        },now::get);
        check(source.discover().length()==1,"truncated recursive tree falls back to complete directory traversal");
        int count=calls.size();source.discover();check(calls.size()==count,"one-minute discovery cache");
        now.addAndGet(60_001);source.discover();check(calls.size()==count+1,"unchanged revision avoids tree refetch");
        version.incrementAndGet();now.addAndGet(60_001);
        check(source.discover().length()==2,"new nested judge folder discovered after revision change");
        var hits=new java.util.concurrent.atomic.AtomicInteger();
        GitHubSource limited=new GitHubSource(request->{hits.incrementAndGet();return new GitHubSource.Reply(429,new byte[0],HttpHeaders.of(Map.of("retry-after",List.of("120")),(a,b)->true));},now::get);
        try { limited.discover();throw new AssertionError("rate limit accepted"); } catch(DatasetService.Failure e){check(e.status==429,"rate limit reported");}
        try { limited.discover();throw new AssertionError("backoff skipped"); } catch(DatasetService.Failure e){check(hits.get()==1,"retry-after prevents extra requests");}
    }
    public static void main(String[] args) throws Exception {
        githubTests();
        byte[] before = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of("telemetry.csv")));
        Source source = new Source();
        source.folders.put("healthy",fixture(false,50,"UNSEEN_A"));
        source.folders.put("fault",fixture(true,50,"UNSEEN_A"));
        source.folders.put("high",fixture(true,80,"UNSEEN_A"));
        source.folders.put("new/judge",fixture(true,60,"JUDGE_99"));
        var badCsv = new HashMap<>(fixture(false,50,"UNSEEN_A"));badCsv.put("telemetry.csv","not,a,valid,recording".getBytes(StandardCharsets.UTF_8));
        source.folders.put("bad-csv",badCsv);
        var degradation = new HashMap<>(fixture(false,50,"UNSEEN_A"));
        degradation.put("telemetry.csv",new String(degradation.get("telemetry.csv"),StandardCharsets.UTF_8).replace("flow_bpd,1000","flow_bpd,600").replace("discharge_psi,1150","discharge_psi,1100").getBytes(StandardCharsets.UTF_8));
        source.folders.put("degradation",degradation);
        var leak = new HashMap<>(fixture(false,50,"UNSEEN_A"));
        leak.put("telemetry.csv",new String(leak.get("telemetry.csv"),StandardCharsets.UTF_8).replace("receive_flow_bpd,1000","receive_flow_bpd,800").replace("pressure_psi,1050","pressure_psi,1010").getBytes(StandardCharsets.UTF_8));
        source.folders.put("leak",leak);
        source.folders.put("bad",Map.of("network.json","bad json".getBytes()));
        DatasetService service = new DatasetService(source);
        JSONObject healthy=service.analyze(PREFIX+"healthy");
        check(healthy.getJSONArray("events").isEmpty(),"zero events succeeds");
        check(healthy.getLong("as_of_unix")==1_800_000_900L,"telemetry endpoint time");
        JSONObject fault=service.analyze(PREFIX+"fault");
        check(fault.getJSONArray("events").length()==1,"changing only telemetry creates an incident");
        JSONObject lowEvent=fault.getJSONArray("events").getJSONObject(0);
        check(lowEvent.getString("category").equals("pump_failure"),"actual fault detection");
        double lowHours=lowEvent.getJSONObject("hours_to_limit").getDouble("p50");
        JSONObject high=service.analyze(PREFIX+"high");
        check(high.getJSONArray("events").getJSONObject(0).getJSONObject("hours_to_limit").getDouble("p50")<lowHours,"countdown responds to tank telemetry");
        expectFailure(()->service.network(fault.getString("dataset_id")),409);
        String id=high.getString("dataset_id");
        for (String sort:List.of("priority","recent","cost")) check(service.events(id,sort).getJSONArray("events").length()==1,"sort includes full events");
        try { service.analyze(PREFIX+"bad"); throw new AssertionError("malformed JSON accepted"); } catch (JSONException expected) {}
        check(service.network(id).getString("dataset_id").equals(id),"failed load preserves snapshot");
        try { service.analyze(PREFIX+"missing"); throw new AssertionError("missing download accepted"); } catch (IOException expected) {}
        check(service.network(id).getString("dataset_id").equals(id),"failed download preserves snapshot");
        try { service.analyze(PREFIX+"bad-csv");throw new AssertionError("malformed CSV accepted"); } catch(IOException expected) {}
        check(service.network(id).getString("dataset_id").equals(id),"malformed CSV preserves snapshot");
        check(service.analyze(PREFIX+"degradation").getJSONArray("events").getJSONObject(0).getString("category").equals("pump_degradation"),"degradation rule retained");
        check(service.analyze(PREFIX+"leak").getJSONArray("events").getJSONObject(0).getString("category").equals("pipeline_leak"),"leak rule retained");
        JSONObject unseen=service.analyze(PREFIX+"new/judge");
        check(unseen.getJSONArray("events").getJSONObject(0).getString("system_id").equals("JUDGE_99"),"unfamiliar IDs computed");
        check(service.network(unseen.getString("dataset_id")).getJSONObject("network").getJSONArray("systems").getJSONObject(0).getJSONArray("center").getDouble(0)==39.7,"new coordinates retained");
        int size=source.discover().length(); source.folders.put("added-later",fixture(false,50,"LATER"));
        check(source.discover().length()==size+1,"new folders discoverable without restart");
        Map<String,Set<String>> found=new TreeMap<>();
        GitHubSource.collect(new JSONArray().put(new JSONObject().put("type","blob").put("path","deep/judge/network.json"))
            .put(new JSONObject().put("type","blob").put("path","deep/judge/ops.json"))
            .put(new JSONObject().put("type","blob").put("path","deep/judge/telemetry.csv")),"",found);
        check(found.get("deep/judge").size()==3,"recursive tree groups nested folders");
        source.folders.put("slow",fixture(true,50,"SLOW"));
        ExecutorService executor=Executors.newSingleThreadExecutor();
        Future<?> slow=executor.submit(()->{try{service.analyze(PREFIX+"slow");throw new AssertionError("old request published");}catch(DatasetService.Failure e){check(e.status==409,"superseded request rejected");}catch(Exception e){throw new RuntimeException(e);}});
        check(source.started.await(5,TimeUnit.SECONDS),"slow analysis started");
        JSONObject latest=service.analyze(PREFIX+"healthy");source.release.countDown();slow.get(10,TimeUnit.SECONDS);executor.shutdownNow();
        check(service.network(latest.getString("dataset_id")).getString("dataset_path").equals("healthy"),"newest analysis wins");
        Event a=event("a","ongoing","pump_failure",2.0,100.0,10,1), b=event("b","ongoing","pump_failure",2.0,200.0,20,2),
            c=event("c","ongoing","pipeline_leak",null,null,0,3), d=event("d","resolved","pump_failure",null,300.0,0,4);
        Event[] events={d,c,a,b};Arrays.sort(events,EventSort.PRIORITY);check(events[0]==b && events[1]==a && events[2]==c && events[3]==d,"priority countdown/revenue/category/status");
        a.hoursToLimit=1.0;check(EventSort.PRIORITY.compare(a,b)<0,"shortest countdown first");
        Arrays.sort(events,EventSort.COST);check(events[0]==d && events[3]==c,"cost descending null last");
        Arrays.sort(events,EventSort.RECENT);check(events[0]==d && events[3]==a,"recent descending");
        b.onsetUnix=a.onsetUnix;check(EventSort.RECENT.compare(a,b)<0,"stable ID tie breaker");
        var server=WebServer.create(new InetSocketAddress("localhost",0),service);server.start();
        try {
            HttpClient client=HttpClient.newHttpClient();String base="http://localhost:"+server.getAddress().getPort();
            var response=client.send(HttpRequest.newBuilder(URI.create(base+"/api/getevents")).POST(HttpRequest.BodyPublishers.ofString(new JSONObject().put("github-repo",PREFIX+"healthy").toString())).build(),HttpResponse.BodyHandlers.ofString());
            check(response.statusCode()==200,"HTTP analysis succeeds with zero events");
            check(response.headers().firstValue("content-type").orElse("").startsWith("application/json"),"JSON content type");
            String datasetId=new JSONObject(response.body()).getString("dataset_id");
            for(String route:List.of("getnetwork","resortevents","exportevents")) {
                var result=client.send(HttpRequest.newBuilder(URI.create(base+"/api/"+route+"?dataset_id="+datasetId+"&pass-params=cost")).build(),HttpResponse.BodyHandlers.ofString());
                check(result.statusCode()==200 && new JSONObject(result.body()).getString("dataset_id").equals(datasetId),"HTTP snapshot consistency: "+route);
            }
            check(client.send(HttpRequest.newBuilder(URI.create(base+"/api/getevents")).build(),HttpResponse.BodyHandlers.ofString()).statusCode()==405,"HTTP method enforcement");
            check(client.send(HttpRequest.newBuilder(URI.create(base+"/api/getevents")).POST(HttpRequest.BodyPublishers.ofString("{")).build(),HttpResponse.BodyHandlers.ofString()).statusCode()==400,"invalid request JSON");
            check(client.send(HttpRequest.newBuilder(URI.create(base+"/api/getnetwork?dataset_id=stale")).build(),HttpResponse.BodyHandlers.ofString()).statusCode()==409,"HTTP stale snapshot conflict");
            var logo=client.send(HttpRequest.newBuilder(URI.create(base+"/images/Watchpot.svg")).build(),HttpResponse.BodyHandlers.ofByteArray());
            check(logo.statusCode()==200 && logo.headers().firstValue("content-type").orElse("").equals("image/svg+xml"),"SVG MIME type");
            check(Arrays.equals(logo.body(),Files.readAllBytes(Path.of("websiteFiles/images/Watchpot.svg"))),"SVG served byte-for-byte");
            check(client.send(HttpRequest.newBuilder(URI.create(base+"/images/missing.png")).build(),HttpResponse.BodyHandlers.ofString()).statusCode()==404,"missing assets return 404");
            check(client.send(HttpRequest.newBuilder(URI.create(base+"/api/resortevents?dataset_id="+datasetId+"&pass-params=invalid")).build(),HttpResponse.BodyHandlers.ofString()).statusCode()==400,"invalid query sort rejected");
        } finally { server.stop(0);((ExecutorService)server.getExecutor()).shutdownNow(); }
        check(source.requests.stream().allMatch(Set.of("network.json","ops.json","telemetry.csv")::contains),"only three input data files requested");
        check(Arrays.equals(before,MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of("telemetry.csv")))),"tracked telemetry unchanged");
        System.out.println("PASS: "+checks+" integration assertions");
    }
}
