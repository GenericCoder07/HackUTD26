package WebServer;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executors;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import WebToolkit.APIHandler;
import WebToolkit.DirectoryHandler;
import WebToolkit.FileHandler;

public class WebServer {
    @FunctionalInterface private interface Endpoint {
        JSONObject call(JSONObject body, Map<String,String> query) throws IOException, InterruptedException;
    }
    private static void endpoint(HttpServer server, String path, String method, Endpoint endpoint) {
        APIHandler handler = new APIHandler(method) {
            protected JSONObject call(JSONObject body, Map<String,String> query) throws IOException, InterruptedException {
                return endpoint.call(body, query);
            }
        };
        server.createContext(path, exchange -> {
            if (!path.equals(exchange.getRequestURI().getPath())) FileHandler.sendError(exchange,404,"Unknown API endpoint");
            else handler.handle(exchange);
        });
    }
    public static HttpServer create(InetSocketAddress address, DatasetService service) throws IOException {
        HttpServer server = HttpServer.create(address, 0);
        server.setExecutor(Executors.newFixedThreadPool(10));
        endpoint(server, "/api/datasets", "GET", (body, query) -> new JSONObject().put("datasets", service.discover()));
        endpoint(server, "/api/getevents", "POST", (body, query) -> service.analyze(body.getString("github-repo")));
        endpoint(server, "/api/getnetwork", "GET", (body, query) -> service.network(query.get("dataset_id")));
        endpoint(server, "/api/resortevents", "GET", (body, query) -> service.events(query.get("dataset_id"), query.getOrDefault("pass-params", "priority")));
        endpoint(server, "/api/exportevents", "GET", (body, query) -> service.events(query.get("dataset_id"), "priority"));
        server.createContext("/api/", exchange -> FileHandler.sendError(exchange, 404, "Unknown API endpoint"));
        for (String directory : new String[]{"css", "js", "images", "vendor"})
            server.createContext("/" + directory, new DirectoryHandler("/" + directory, new File("websiteFiles")));
        server.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/")) new FileHandler(new File("websiteFiles/html/index.html")).handle(exchange);
            else FileHandler.sendError(exchange, 404, "File not found");
        });
        return server;
    }
    public static void main(String[] args) throws IOException {
        HttpServer server = create(new InetSocketAddress("localhost", 8080), new DatasetService(new GitHubSource()));
        server.start();
        System.out.println("Watchpot is running at http://localhost:8080/ (demo: /?mode=demo)");
    }
}
