package WebToolkit;

import java.io.File;
import java.io.IOException;

import com.sun.net.httpserver.HttpExchange;

public class DirectoryHandler implements Handler
{
	private File linkedDirectory;
	private String parentRequestString;
	public DirectoryHandler(String parentRequestString, File linkedDirectory)
	{
		this.linkedDirectory = linkedDirectory;
		this.parentRequestString = parentRequestString;
	}
	
	public void handle(HttpExchange exchange) throws IOException
	{
		String path = exchange.getRequestURI().getPath();
		String finalPath = linkedDirectory.getPath() + path.substring(path.indexOf("/", path.indexOf(parentRequestString)));
		
		System.out.println("|---- Got DirectoryHandler request [" + exchange.getRequestURI().getPath() + "] sending file [" + finalPath + "]");
		
		File requestedFile = new File(finalPath);
		if(requestedFile.exists())
			new FileHandler(requestedFile).handle(exchange);
		else
			FileHandler.NOTFOUND.handle(exchange);
	}
}
