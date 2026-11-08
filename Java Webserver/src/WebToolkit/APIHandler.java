package WebToolkit;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONException;
import org.json.JSONObject;
import WebServer.DatasetService;

public abstract class APIHandler implements HttpHandler {
    private final String method;
    protected APIHandler(String method) { this.method = method; }
    public final void handle(HttpExchange exchange) throws IOException {
        JSONObject response;
        int status = 200;
        try {
            if (!method.equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", method);
                throw new DatasetService.Failure(405, "Use " + method + " for this endpoint");
            }
            Map<String, String> query = new TreeMap<>();
            String raw = exchange.getRequestURI().getRawQuery();
            if (raw != null) for (String part : raw.split("&")) {
                String[] pair = part.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                if (query.put(key, pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "") != null)
                    throw new IllegalArgumentException("Duplicate query parameter");
            }
            JSONObject body = new JSONObject();
            if ("POST".equals(method)) {
                byte[] bytes = exchange.getRequestBody().readNBytes(65_537);
                if (bytes.length > 65_536) throw new DatasetService.Failure(413, "Request body is too large");
                body = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            }
            response = call(body, query);
        } catch (DatasetService.Failure e) { status = e.status; response = error(e.getMessage());
        } catch (JSONException | IllegalArgumentException e) { status = 400; response = error("Invalid input: " + e.getMessage());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); status = 503; response = error("Request interrupted. Retry analysis.");
        } catch (IOException e) { status = 502; response = error(e.getMessage());
        } catch (RuntimeException e) { status = 500; response = error("Analysis failed. Check the dataset and retry."); e.printStackTrace(); }
        response.put("response", status).put("success", status == 200);
        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
    private JSONObject error(String message) { return new JSONObject().put("response-text", message); }
    protected abstract JSONObject call(JSONObject body, Map<String, String> query) throws IOException, InterruptedException;
}
