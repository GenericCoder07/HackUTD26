package WebToolkit;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.JOptionPane;

import com.sun.net.httpserver.HttpExchange;

public class FileHandler implements Handler
{
	public static final FileHandler NOTFOUND = new FileHandler(new File("websiteFiles/errorPages/notFound.html"));

	private File file;
	public FileHandler(File linkedFile)
	{
		file = linkedFile;
	}

	public void handle(HttpExchange exchange) throws IOException
	{
		Path path = file.toPath();
		String contentType = getContentType(file.getName());

		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(200, Files.size(path));

		try (OutputStream response = exchange.getResponseBody())
		{
			Files.copy(path, response);
		}
	}

	private String getContentType(String name)
	{
		String lower = name.toLowerCase();

		if (lower.endsWith(".html")) 							return "text/html; charset=UTF-8";
		if (lower.endsWith(".css"))  							return "text/css; charset=UTF-8";
		if (lower.endsWith(".js"))   							return "text/javascript; charset=UTF-8";
		if (lower.endsWith(".svg"))  							return "image/svg+xml";
		if (lower.endsWith(".ico"))  							return "image/x-icon";
		if (lower.endsWith(".png"))  							return "image/png";
		if (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))	return "image/jpeg";

		return "application/octet-stream";
	}

	public static void showError(String title, String message)
	{
		JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE);
	}
}
