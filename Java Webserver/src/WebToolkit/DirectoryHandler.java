package WebToolkit;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import com.sun.net.httpserver.HttpExchange;

public class DirectoryHandler implements Handler {
    private final Path root;
    private final String prefix;
    public DirectoryHandler(String prefix, File root) { this.prefix = prefix; this.root = root.toPath().toAbsolutePath().normalize(); }
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(prefix + "/")) { FileHandler.sendError(exchange, 404, "File not found"); return; }
        Path file = root.resolve(path.substring(1)).normalize();
        if (!file.startsWith(root)) { FileHandler.sendError(exchange, 404, "File not found"); return; }
        new FileHandler(file.toFile()).handle(exchange);
    }
}
