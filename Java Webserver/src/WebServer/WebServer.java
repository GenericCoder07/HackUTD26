package WebServer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import javax.swing.JOptionPane;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import com.sun.net.httpserver.HttpServer;

import WebToolkit.APIHandler;
import WebToolkit.DirectoryHandler;
import WebToolkit.FileHandler;

public class WebServer
{
	public static final String ADMIN = "EOG2026";
	public static ConcurrentHashMap<String, LocalDateTime> adminSessions = new ConcurrentHashMap<>();
	
	@SuppressWarnings("resource")
	public static void main(String[] args) throws IOException
	{
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 8080), 0);
		server.setExecutor(Executors.newFixedThreadPool(10));
		
		server.start();
		server.createContext("/", new FileHandler(new File("websiteFiles/html/index.html")));
		server.createContext("/api/verifyadmin", new APIHandler() {

			public JSONObject handleAPICall(JSONObject JSON)
			{
				if(!"POST".equals(getStringOrDefault(JSON, "request-method", "")))
					return badResponse("Only POST is supported by this endpoint", 405);
				
				String adminPassword = getStringOrDefault(JSON, "admin-password", "");
				
				byte[] buf = new byte[32];
				new SecureRandom().nextBytes(buf);
				String session = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
				
				LocalDateTime expire = LocalDateTime.now().plusMinutes(30);
				
				if(adminPassword.equals(ADMIN))
					adminSessions.put(session, expire);
				
				JSONObject ret = new JSONObject();
				ret.put("response", 200);
				ret.put("success", adminPassword.equals(ADMIN));
				ret.put("admin-session", adminSessions.containsKey(session) ? session : null);
				ret.put("response-text", "Admin check returned");
				
				return ret;
			}
			
		});
		server.createContext("/api/processdata", new APIHandler() {

			public JSONObject handleAPICall(JSONObject JSON)
			{
				if(!"POST".equals(getStringOrDefault(JSON, "request-method", "")))
					return badResponse("Only POST is supported by this endpoint", 405);
				
				String adminSession = getStringOrDefault(JSON, "admin-session", null);
				
				LocalDateTime expiresAt = adminSession == null ? null : adminSessions.remove(adminSession);
				if (expiresAt == null || !expiresAt.isAfter(LocalDateTime.now()))
					   return badResponse("Invalid permissions", 403);
				
				
				String githubRepo = getStringOrDefault(JSON, "github-repo", null);
				if(githubRepo == null)
					return badResponse("GitHub Repo link not included");
				
				
				if(!loadGithub(githubRepo))
					return badResponse("GitHub Repo Get Failed");
				
				processAllPending();
				
				JSONObject ret = new JSONObject();
				ret.put("response", 200);
				ret.put("success", true);
				ret.put("response-text", "Events successfully processed");
				
				return ret;
			}
			
		});
		server.createContext("/api/getevents", new APIHandler() {

			public JSONObject handleAPICall(JSONObject JSON)
			{
				String dataset = getStringOrDefault(JSON, "dataset", null);
				if(dataset == null)
					return badResponse("Dataset not included");
				
				String sortParam = getStringOrDefault(JSON, "sort-param", "RECENT");
				Event.sort = EventSort.valueOf(sortParam);
				
				processAllPending();
				
				JSONArray array = getJSONDataAsArray(new File(dataset), "events");
				array = sortEventsArray(array);
				
				JSONObject ret = new JSONObject();
				ret.put("events", array);
				ret.put("response", 200);
				ret.put("success", true);
				ret.put("response-text", "Events successfully set");
				
				return ret;
			}
			
		});
		server.createContext("/api/getnetwork", new APIHandler() {

			public JSONObject handleAPICall(JSONObject JSON)
			{
				String dataset = getStringOrDefault(JSON, "dataset", null);
				if(dataset == null)
					return badResponse("Dataset not included");
				
				JSONObject ret = new JSONObject();
				ret.put("network", getJSONData(new File(dataset), "network"));
				ret.put("response", 200);
				ret.put("success", true);
				ret.put("response-text", "Events successfully set");
				
				return ret;
			}
			
		});
		server.createContext("/api/getdatasets", new APIHandler() {

			public JSONObject handleAPICall(JSONObject JSON)
			{
				String[] datasets = new File("websiteFiles/data").list();
				
				JSONArray datasetNames = new JSONArray();
				for(String datasetName : datasets)
					datasetNames.put(datasetName);
				
				JSONObject ret = new JSONObject();
				ret.put("datasets", datasetNames);
				ret.put("response", 200);
				ret.put("success", true);
				ret.put("response-text", "Events successfully set");
				
				return ret;
			}
			
		});
		
		server.createContext("/css", new DirectoryHandler("/css", new File("websiteFiles")));
		server.createContext("/vendor", new DirectoryHandler("/vendor", new File("websiteFiles/js")));
		server.createContext("/js", new DirectoryHandler("/js", new File("websiteFiles")));
		server.createContext("/images", new DirectoryHandler("/images", new File("websiteFiles")));
		server.createContext("/favicon.ico", new FileHandler(new File("websiteFiles/favicon.ico")));
	}

	public static JSONArray sortEventsArray(JSONArray array)
	{
		Event[] events = new Event[array.length()];
		
		for(int i = 0; i < events.length; i++)
			events[i] = new Event(array.getJSONObject(i));
		
		Arrays.sort(events);
		
		array = new JSONArray();
		for(Event e : events)
			array.put(e.toJSON());
		
		return array;
	}

	public static void processAllPending()
	{
		File f = new File("websiteFiles/data");
		
		File[] datasets = f.listFiles();
		for(File dataset : datasets)
		{
			if(!isProcessed(dataset))
				processDataset(dataset);
		}
	}
	
	public static void processDataset(File dataset)
	{
		JSONObject networkJSON = getJSONData(dataset, "network");
		JSONObject opsJSON = getJSONData(dataset, "ops");
		
		try
		{
			Event[] events = new EventAnalyzer(networkJSON, opsJSON).analyze(Path.of(dataset.getAbsolutePath() + "/telemetry.csv"));

			JSONArray eventsArray = new JSONArray();
			for(Event e : events)
				eventsArray.put(e.toJSON());
			
			File eventsJSON = new File(dataset.getAbsolutePath() + "/events.json");
			BufferedWriter writer = new BufferedWriter(new FileWriter(eventsJSON));
			writer.append(eventsArray.toString());
			writer.close();
			
		}
		catch(IOException | IllegalArgumentException | JSONException exception)
		{
			System.err.println("Event analysis failed: " + exception.getMessage());
		}
	}

	public static JSONObject getJSONData(File dataset, String dataName)
	{
		try
		{
			FileReader fileRead = new FileReader(new File(dataset.getAbsolutePath() + "/" + dataName + ".json"));
			JSONTokener tokenizer = new JSONTokener(fileRead);
			JSONObject o = new JSONObject(tokenizer);
			fileRead.close();
			
			return o;
		} 
		catch (IOException e)
		{
		}
		
		return new JSONObject();
	}
	
	public static JSONArray getJSONDataAsArray(File dataset, String dataName)
	{
		try
		{
			FileReader fileRead = new FileReader(new File(dataset.getAbsolutePath() + "/" + dataName + ".json"));
			JSONTokener tokenizer = new JSONTokener(fileRead);
			JSONArray o = new JSONArray(tokenizer);
			fileRead.close();
			
			return o;
		} 
		catch (IOException e)
		{
		}
		
		return new JSONArray();
	}
	
	public static boolean isProcessed(File dataset)
	{
		String[] names = dataset.list();
		for(String str : names)
			if(str.equals("events.json"))
				return true;
		
		return false;
	}

	@SuppressWarnings("resource")
	public static boolean loadGithub(String github)
	{
		if(github == null)
			return false;

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

			String rawDirectory = "https://raw.githubusercontent.com/" + encodePathPart(owner) + "/" + encodePathPart(repository) + "/" + encodePathPart(branch) + "/" + directory + "/";
			HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
			
			byte[] networkData = download(client, URI.create(rawDirectory + "network.json"));
			byte[] opsData = download(client, URI.create(rawDirectory + "ops.json"));
			byte[] telemetryData = download(client, URI.create(rawDirectory + "telemetry.csv"));

			String datasetName = parts[parts.length - 1];
			if(datasetName.isBlank() || ".".equals(datasetName) || "..".equals(datasetName))
				return false;
			File dataDirectory = new File("websiteFiles/data").getCanonicalFile();
			File datasetDirectory = new File(dataDirectory, datasetName).getCanonicalFile();
			if(!dataDirectory.equals(datasetDirectory.getParentFile()))
				return false;
			if(!datasetDirectory.exists() && !datasetDirectory.mkdirs())
				return false;

			File networkJSON = new File(datasetDirectory, "network.json");
			BufferedWriter writer = new BufferedWriter(new FileWriter(networkJSON));
			writer.append(new String(networkData, StandardCharsets.UTF_8));
			writer.close();
			
			File opsJSON = new File(datasetDirectory, "ops.json");
			writer = new BufferedWriter(new FileWriter(opsJSON));
			writer.append(new String(opsData, StandardCharsets.UTF_8));
			writer.close();
			
			File telemetryCSV = new File(datasetDirectory, "telemetry.csv");
			writer = new BufferedWriter(new FileWriter(telemetryCSV));
			writer.append(new String(telemetryData, StandardCharsets.UTF_8));
			writer.close();

			File eventsJSON = new File(datasetDirectory, "events.json");
			if(eventsJSON.exists() && !eventsJSON.delete())
				return false;
			
			return true;
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
