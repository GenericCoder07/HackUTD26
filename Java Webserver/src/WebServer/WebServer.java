package WebServer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;

import javax.swing.JOptionPane;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.sun.net.httpserver.HttpServer;

import WebToolkit.APIHandler;
import WebToolkit.DirectoryHandler;
import WebToolkit.FileHandler;

public class WebServer
{
	static JSONObject networkJSON;
	static JSONObject opsJSON;
	
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
					return badResponse("GitHub Repo Get Failed");
				
				Event[] events = getEvents();
				
				if(events == null || events.length == 0)
					return badResponse("Event Get Failed");
				
				JSONArray eventsArray = new JSONArray();
				for(Event e : events)
					eventsArray.put(e.toJSON());
				
				System.out.println(eventsArray);
				
				JSONObject ret = new JSONObject();
				ret.put("events", eventsArray);
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
				ret.put("network", networkJSON);
				ret.put("response", 200);
				ret.put("success", true);
				ret.put("response-text", "Events successfully set");
				
				return ret;
			}
			
		});
		server.createContext("/api/exportevents", new APIHandler() {

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

		server.createContext("/css", new DirectoryHandler("/css", new File("websiteFiles")));
		server.createContext("/js", new DirectoryHandler("/js", new File("websiteFiles")));
		server.createContext("/images", new DirectoryHandler("/images", new File("websiteFiles")));
		server.createContext("/favicon.ico", new FileHandler(new File("websiteFiles/favicon.ico")));
	}

	public static Event[] getEvents()
	{
		try
		{
			return new EventAnalyzer(networkJSON, opsJSON).analyze(Path.of("telemetry.csv"));
		}
		catch(IOException | IllegalArgumentException | JSONException exception)
		{
			System.err.println("Event analysis failed: " + exception.getMessage());
			return null;
		}
	}

	@SuppressWarnings("resource")
	public static boolean loadGithub(String github)
	{
		if(github == null)
			return false;

		Path temporaryCSV = null;
		try
		{
			URI folder = URI.create(github.trim());
			if(!"https".equalsIgnoreCase(folder.getScheme()) || !"github.com".equalsIgnoreCase(folder.getHost()))
				return false;

			String[] parts = folder.getPath().split("/");
			if(parts.length < 6 || !"tree".equals(parts[3]))
				return false;

			String owner = parts[1];
			String repository = parts[2];
			String branch = parts[4];
			StringBuilder directory = new StringBuilder();
			for(int i = 5; i < parts.length; i++)
			{
				if(parts[i].isEmpty())
					continue;
				if(directory.length() > 0)
					directory.append('/');
				directory.append(encodePathPart(parts[i]));
			}
			if(owner.isEmpty() || repository.isEmpty() || branch.isEmpty() || directory.length() == 0)
				return false;

			String rawDirectory = "https://raw.githubusercontent.com/"
					+ encodePathPart(owner) + "/"
					+ encodePathPart(repository) + "/"
					+ encodePathPart(branch) + "/"
					+ directory + "/";

			HttpClient client = HttpClient.newBuilder()
					.followRedirects(HttpClient.Redirect.NORMAL)
					.build();
			byte[] networkData = download(client, URI.create(rawDirectory + "network.json"));
			byte[] opsData = download(client, URI.create(rawDirectory + "ops.json"));
			byte[] telemetryData = download(client, URI.create(rawDirectory + "telemetry.csv"));

			networkJSON = new JSONObject(new String(networkData, StandardCharsets.UTF_8));
			opsJSON = new JSONObject(new String(opsData, StandardCharsets.UTF_8));

			Path telemetryCSV = Path.of("telemetry.csv").toAbsolutePath();
			temporaryCSV = Files.createTempFile(telemetryCSV.getParent(), ".telemetry-", ".csv.tmp");
			Files.write(temporaryCSV, telemetryData);
			try
			{
				Files.move(temporaryCSV, telemetryCSV, StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
			}
			catch(AtomicMoveNotSupportedException exception)
			{
				Files.move(temporaryCSV, telemetryCSV, StandardCopyOption.REPLACE_EXISTING);
			}
			temporaryCSV = null;

			System.out.println(networkJSON);
			System.out.println(opsJSON);
			
			return networkJSON != null && opsJSON != null;
		}
		catch(IllegalArgumentException | IOException | JSONException exception)
		{
			return false;
		}
		catch(InterruptedException exception)
		{
			Thread.currentThread().interrupt();
			return false;
		}
		finally
		{
			if(temporaryCSV != null)
			{
				try
				{
					Files.deleteIfExists(temporaryCSV);
				}
				catch(IOException exception)
				{
					// The failed temporary download can be cleaned up later.
				}
			}
		}
	}

	private static byte[] download(HttpClient client, URI file) throws IOException, InterruptedException
	{
		HttpRequest request = HttpRequest.newBuilder(file).GET().build();
		HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if(response.statusCode() < 200 || response.statusCode() >= 300)
			throw new IOException("GitHub returned HTTP " + response.statusCode());
		return response.body();
	}

	private static String encodePathPart(String part)
	{
		return URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20");
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
