package com.leaguescape.wiki;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.inject.Singleton;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

/**
 * Fetches OSRS item list (id + name) from the osrsbox items-summary API.
 * Use this when building task lists or assigning tasks to obtain items.
 * <p>
 * Items summary URL: {@value #ITEMS_SUMMARY_URL}
 * <p>
 * JSON shape: root object with string keys (item id), each value is
 * <code>{"id": &lt;number&gt;, "name": "&lt;string&gt;"}</code>.
 */
@Slf4j
@Singleton
public class OsrsItemService
{
	/** Full list of items: id and name. JSON object keyed by id, values are { "id", "name" }. */
	public static final String ITEMS_SUMMARY_URL = "https://www.osrsbox.com/osrsbox-db/items-summary.json";

	private static final String USER_AGENT = "LeagueScape/1.0 (OSRS items; RuneLite plugin)";
	private static final int REQUEST_TIMEOUT_MS = 30_000;

	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "LeagueScape-OsrsItems");
		t.setDaemon(true);
		return t;
	});

	@Inject
	public OsrsItemService()
	{
	}

	/**
	 * Fetch the full items summary (id + name for all items). Blocks; use from background or cache result.
	 *
	 * @return list of all items (id, name), or empty list on error
	 */
	public List<OsrsItemSummary> getItemsSummary()
	{
		try
		{
			String json = httpGet(ITEMS_SUMMARY_URL);
			if (json == null) return new ArrayList<>();
			JsonObject root = new JsonParser().parse(json).getAsJsonObject();
			List<OsrsItemSummary> out = new ArrayList<>(root.size());
			for (String key : root.keySet())
			{
				JsonElement el = root.get(key);
				if (el == null || !el.isJsonObject()) continue;
				JsonObject obj = el.getAsJsonObject();
				if (!obj.has("id") || !obj.has("name")) continue;
				out.add(new OsrsItemSummary(
					obj.get("id").getAsInt(),
					obj.get("name").getAsString()
				));
			}
			return out;
		}
		catch (Exception e)
		{
			log.warn("OsrsItemService getItemsSummary failed: {}", e.getMessage());
			return new ArrayList<>();
		}
	}

	/**
	 * Fetch the full items summary on a background thread.
	 */
	public CompletableFuture<List<OsrsItemSummary>> getItemsSummaryAsync()
	{
		return CompletableFuture.supplyAsync(this::getItemsSummary, executor);
	}

	private String httpGet(String urlString)
	{
		try (InputStream in = httpGetStream(urlString))
		{
			if (in == null) return null;
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			log.debug("OsrsItemService HTTP GET failed: {}", e.getMessage());
			return null;
		}
	}

	private InputStream httpGetStream(String urlString)
	{
		try
		{
			URI uri = URI.create(urlString);
			java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
				.connectTimeout(java.time.Duration.ofMillis(REQUEST_TIMEOUT_MS))
				.followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
				.build();
			java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(uri)
				.timeout(java.time.Duration.ofMillis(REQUEST_TIMEOUT_MS))
				.header("User-Agent", USER_AGENT)
				.GET()
				.build();
			java.net.http.HttpResponse<InputStream> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() != 200) return null;
			return response.body();
		}
		catch (Exception e)
		{
			log.debug("OsrsItemService HTTP request failed: {}", e.getMessage());
			return null;
		}
	}
}
