package WebToolkit;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;

import javax.swing.JOptionPane;

import com.sun.net.httpserver.HttpExchange;

public class FileHandler implements Handler
{
	public static final FileHandler NOTFOUND = new FileHandler(new File("websiteFiles/errorPages/notFound.html"));
	
	private HashMap<String, String> endpointContentTypes;
	{
		endpointContentTypes = new HashMap<>();
		endpointContentTypes.put(".css", "text/css");
		endpointContentTypes.put(".js", "text/javascript");
		endpointContentTypes.put(".html", "text/html");
	}
	
	private File file;
	public FileHandler(File linkedFile)
	{
		file = linkedFile;
	}
	
	public void handle(HttpExchange exchange) throws IOException
	{
		System.out.println("|---- Got FileHandler request [" + exchange.getRequestURI().getPath() + "] sending file [" + file.getPath() + "]");
		
		StringBuilder builder = new StringBuilder();
		try (BufferedReader contentReader = new BufferedReader(new FileReader(file))) 
		{
			String line;
			while ((line = contentReader.readLine()) != null)
				builder.append(line).append("\n");
		}
		catch(IOException e) {showError("IOException", "Error when reading file /" + file.getName() + " - \"" + e.getMessage() + "\"");}

		String content = builder.toString();
		
		String fileType = file.getName().substring(file.getName().indexOf("."));
		
		if(endpointContentTypes.get(fileType) != null)
			exchange.getResponseHeaders().set("Content-Type", endpointContentTypes.get(fileType) + "; charset=UTF-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		
		exchange.sendResponseHeaders(200, content.getBytes().length);

		try (OutputStream os = exchange.getResponseBody()) 
		{
			os.write(content.getBytes());
			os.close();
		}
		catch(IOException e) {showError("IOException", "Error when outputing response to file request /" + file.getName() + " - \"" + e.getMessage() + "\"");}
	}
	
	public static void showError(String title, String message)
	{
		JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE);
	}
}
