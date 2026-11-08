package WebServer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.concurrent.Executors;

import javax.swing.JOptionPane;

import org.json.JSONArray;
import org.json.JSONObject;

import com.sun.net.httpserver.HttpServer;

import Database.StorageTech;
import WebToolkit.APIHandler;
import WebToolkit.DirectoryHandler;
import WebToolkit.FileHandler;

public class WebServer
{
	static HashMap<String, User> users = new HashMap<>();
	static StorageTech userDatabase;
	@SuppressWarnings("resource")
	public static void main(String[] args) throws IOException
	{
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 8080), 0);
		server.setExecutor(Executors.newFixedThreadPool(10));
		
		server.start();
		server.createContext("/", new FileHandler(new File("websiteFiles/html/index.html")));
		server.createContext("/api/getevents", new APIHandler() {

			public JSONObject handleAPICall(JSONObject JSON)
			{
				if(getStringOrDefault(JSON, "response-method", null) != "POST")
					badResponse("Method \"" + getStringOrDefault(JSON, "response-method", null) + "\" not recognised by this endpoint");
				
				String githubRepo = getStringOrDefault(JSON, "github-repo", null);
				if(githubRepo == null)
					return badResponse("GitHub Repo link not included");
				
				if(!loadGithub(githubRepo))
					return badResponse("GitHub Repo link not included");
				
				Event[] events = getEvents();
				
				if(events == null || events.length == 0)
					return badResponse("Event Get Failed");
				
				JSONObject ret = new JSONObject();
				ret.put("response", 200);
				ret.put("success", true);
				ret.put("response-text", "Events successfully set");
				
				return ret;
			}
			
		});
		server.createContext("/api/resortevents", new APIHandler() {

			public JSONObject handleAPICall(JSONObject JSON)
			{
				String sortParam = getStringOrDefault(JSON, "sort-param", "RECENT");
				Event.sort = EventSort.valueOf(sortParam);
				

				JSONObject ret = new JSONObject();
				ret.put("response", 200);
				ret.put("success", true);
				ret.put("response-text", "Events successfully set");
				
				
				return ret;
			}
			
		});
		server.createContext("/api/getnetwork", new APIHandler() {

			public JSONObject handleAPICall(JSONObject JSON)
			{
				
				
				JSONObject ret = new JSONObject();
				ret.put("response", 200);
				ret.put("success", true);
				ret.put("response-text", "Events successfully set");
				
				return null;
			}
			
		});
		

		server.createContext("/css", new DirectoryHandler("/css", new File("websiteFiles")));
		server.createContext("/js", new DirectoryHandler("/js", new File("websiteFiles")));
		server.createContext("/images", new DirectoryHandler("/images", new File("websiteFiles")));
		server.createContext("/favicon.ico", new FileHandler(new File("websiteFiles/favicon.ico")));
	}

	public static Event[] getEvents()
	{
		return null;
	}

	public static boolean loadGithub(String github)
	{
		return false;
	}

	public static String getStringOrDefault(JSONObject JSON, String key, String defaultObject)
	{
		if(JSON.has(key))
			return JSON.getString(key);
		return defaultObject;
	}
	
	public static Integer getIntOrDefault(JSONObject JSON, String key, int defaultObject)
	{
		if(JSON.has(key))
			return JSON.getInt(key);
		return defaultObject;
	}
	
	public static JSONObject getJSONOrDefault(JSONObject JSON, String key, JSONObject defaultObject)
	{
		if(JSON.has(key))
			return JSON.getJSONObject(key);
		return defaultObject;
	}
	
	public static JSONArray getArrayOrDefault(JSONObject JSON, String key, JSONArray defaultObject)
	{
		if(JSON.has(key))
			return JSON.getJSONArray(key);
		return defaultObject;
	}

	public static void showError(String title, String message)
	{
		JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE);
	}
	
}