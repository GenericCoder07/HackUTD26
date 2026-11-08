package WebToolkit;
import com.sun.net.httpserver.HttpExchange;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class FileHandler implements Handler {
    private final Path file;
    private static final Map<String,String> TYPES = Map.ofEntries(
        Map.entry("css", "text/css; charset=UTF-8"), Map.entry("js", "text/javascript; charset=UTF-8"),
        Map.entry("html", "text/html; charset=UTF-8"), Map.entry("svg", "image/svg+xml"),
        Map.entry("png", "image/png"), Map.entry("ico", "image/x-icon"), Map.entry("json", "application/json"), Map.entry("map", "application/json"));
    public FileHandler(File linkedFile) { file = linkedFile.toPath(); }
    public void handle(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET") && !exchange.getRequestMethod().equals("HEAD")) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD"); sendError(exchange, 405, "Method not allowed"); return;
        }
        if (!Files.isRegularFile(file)) { sendError(exchange, 404, "File not found"); return; }
        byte[] bytes = Files.readAllBytes(file);
        String name = file.getFileName().toString();
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        exchange.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(extension, "application/octet-stream"));
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Content-Length", String.valueOf(bytes.length));
        exchange.sendResponseHeaders(200, exchange.getRequestMethod().equals("HEAD") ? -1 : bytes.length);
        try (var out = exchange.getResponseBody()) { if (!exchange.getRequestMethod().equals("HEAD")) out.write(bytes); }
    }
    public static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, exchange.getRequestMethod().equals("HEAD") ? -1 : bytes.length);
        try (var out = exchange.getResponseBody()) { if (!exchange.getRequestMethod().equals("HEAD")) out.write(bytes); }
    }
}
