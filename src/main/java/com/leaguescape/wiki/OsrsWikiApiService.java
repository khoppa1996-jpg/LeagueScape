package com.leaguescape.wiki;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

/**
 * Access to the Old School RuneScape Wiki via its MediaWiki API.
 * <p>
 * API base: https://oldschool.runescape.wiki/api.php
 * Use a descriptive User-Agent; the wiki may block or rate-limit generic clients.
 * <p>
 * Supports: resolving image URLs, fetching images as BufferedImage, search, and page content (for item sources).
 * <p>
 * Exact URLs and JSON response shapes for page content and OpenSearch are documented in docs/WIKI_API.md.
 */
@Slf4j
@Singleton
public class OsrsWikiApiService
{
	private static final String API_BASE = "https://oldschool.runescape.wiki/api.php";
	private static final String USER_AGENT = "LeagueScape/1.0 (OSRS Wiki API; RuneLite plugin)";
	private static final int REQUEST_TIMEOUT_MS = 15_000;

	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "LeagueScape-WikiApi");
		t.setDaemon(true);
		return t;
	});

	@Inject
	public OsrsWikiApiService()
	{
	}

	/**
	 * Resolve the direct URL of a wiki image file.
	 *
	 * @param imageFileName filename with or without "File:" prefix (e.g. "Coal.png" or "File:Coal.png")
	 * @return direct image URL, or null if not found or on error
	 */
	public String getImageUrl(String imageFileName)
	{
		String title = imageFileName.startsWith("File:") ? imageFileName : "File:" + imageFileName;
		String query = "?action=query&titles=" + urlEncode(title) + "&prop=imageinfo&iiprop=url&format=json";
		try
		{
			String json = httpGet(API_BASE + query);
			if (json == null) return null;
			JsonObject root = new JsonParser().parse(json).getAsJsonObject();
			JsonObject pages = root.getAsJsonObject("query").getAsJsonObject("pages");
			for (String id : pages.keySet())
			{
				if ("-1".equals(id)) continue; // missing page
				JsonElement info = pages.getAsJsonObject(id).getAsJsonArray("imageinfo");
				if (info != null && info.isJsonArray() && info.getAsJsonArray().size() > 0)
					return info.getAsJsonArray().get(0).getAsJsonObject().get("url").getAsString();
			}
		}
		catch (Exception e)
		{
			log.warn("Wiki API getImageUrl failed for {}: {}", imageFileName, e.getMessage());
		}
		return null;
	}

	/**
	 * Fetch a wiki image as a BufferedImage. Runs on a background thread.
	 *
	 * @param imageFileName filename (e.g. "Coal.png")
	 * @return CompletableFuture with the image, or null on failure
	 */
	public CompletableFuture<BufferedImage> fetchImageAsync(String imageFileName)
	{
		return CompletableFuture.supplyAsync(() -> fetchImage(imageFileName), executor);
	}

	/**
	 * Fetch a wiki image as a BufferedImage. Blocks; prefer {@link #fetchImageAsync(String)} from the client thread.
	 */
	public BufferedImage fetchImage(String imageFileName)
	{
		String url = getImageUrl(imageFileName);
		if (url == null) return null;
		try (InputStream in = httpGetStream(url))
		{
			return in != null ? ImageIO.read(in) : null;
		}
		catch (IOException e)
		{
			log.warn("Wiki image fetch failed for {}: {}", imageFileName, e.getMessage());
			return null;
		}
	}

	/**
	 * Search the wiki (OpenSearch).
	 *
	 * @param search search string
	 * @param limit max results (1–500)
	 * @return list of page titles (may be empty)
	 */
	public List<String> search(String search, int limit)
	{
		if (search == null || search.isEmpty()) return new ArrayList<>();
		int clamped = Math.max(1, Math.min(500, limit));
		String query = "?action=opensearch&search=" + urlEncode(search) + "&limit=" + clamped + "&format=json";
		try
		{
			String json = httpGet(API_BASE + query);
			if (json == null) return new ArrayList<>();
			JsonArray root = new JsonParser().parse(json).getAsJsonArray();
			// OpenSearch returns [ query, titles[], descriptions[], urls[] ]
			if (root.size() < 2) return new ArrayList<>();
			JsonArray titles = root.get(1).getAsJsonArray();
			List<String> out = new ArrayList<>(titles.size());
			for (JsonElement e : titles) out.add(e.getAsString());
			return out;
		}
		catch (Exception e)
		{
			log.warn("Wiki API search failed for {}: {}", search, e.getMessage());
			return new ArrayList<>();
		}
	}

	/**
	 * Get the raw content of a page (revision content). Optional; use for wiki data or infoboxes.
	 *
	 * @param pageTitle full page title (e.g. "Coal" or "File:Coal.png")
	 * @return page wikitext or null
	 */
	public String getPageContent(String pageTitle)
	{
		String query = "?action=query&prop=revisions&rvprop=content&rvslots=main&titles=" + urlEncode(pageTitle) + "&format=json";
		try
		{
			String json = httpGet(API_BASE + query);
			if (json == null) return null;
			JsonObject root = new JsonParser().parse(json).getAsJsonObject();
			JsonObject pages = root.getAsJsonObject("query").getAsJsonObject("pages");
			for (String id : pages.keySet())
			{
				if ("-1".equals(id)) continue;
				JsonElement revs = pages.getAsJsonObject(id).get("revisions");
				if (revs == null || !revs.isJsonArray() || revs.getAsJsonArray().size() == 0) continue;
				JsonObject rev = revs.getAsJsonArray().get(0).getAsJsonObject();
				// Current format: wikitext in revision.slots.main["*"]
				JsonElement slots = rev.get("slots");
				if (slots != null && slots.isJsonObject())
				{
					JsonElement main = slots.getAsJsonObject().get("main");
					if (main != null && main.isJsonObject())
					{
						JsonElement content = main.getAsJsonObject().get("*");
						if (content != null) return content.getAsString();
					}
				}
				// Legacy format: wikitext in revision["*"]
				JsonElement content = rev.get("*");
				if (content != null) return content.getAsString();
			}
		}
		catch (Exception e)
		{
			log.warn("Wiki API getPageContent failed for {}: {}", pageTitle, e.getMessage());
		}
		return null;
	}

	private static final Pattern INFOBOX_IMAGE = Pattern.compile("\\|\\s*image\\s*=\\s*\\[\\[File:([^\\]|]+)\\]\\]", Pattern.CASE_INSENSITIVE);

	/**
	 * Get the first infobox image filename from a wiki page (e.g. "Coal.png" from |image = [[File:Coal.png]]).
	 *
	 * @param pageTitle full page title (e.g. "Coal")
	 * @return image filename without "File:" prefix, or null if not found
	 */
	public String getFirstInfoboxImageFileName(String pageTitle)
	{
		String content = getPageContent(pageTitle);
		if (content == null) return null;
		Matcher m = INFOBOX_IMAGE.matcher(content);
		return m.find() ? m.group(1).trim() : null;
	}

	/**
	 * Resolve and fetch the main infobox image for a wiki page by search term (e.g. item name).
	 * Runs on the service executor; invoke callback on your thread (e.g. EDT via SwingUtilities.invokeLater).
	 *
	 * @param displayName search term (e.g. "Coal")
	 * @param callback receives the image if found, or null
	 */
	public void fetchItemIconAsync(String displayName, Consumer<BufferedImage> callback)
	{
		if (callback == null) return;
		executor.execute(() -> {
			List<String> titles = search(displayName, 1);
			if (titles.isEmpty()) { callback.accept(null); return; }
			String fileName = getFirstInfoboxImageFileName(titles.get(0));
			if (fileName == null) { callback.accept(null); return; }
			BufferedImage img = fetchImage(fileName);
			callback.accept(img);
		});
	}

	private static String urlEncode(String s)
	{
		return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
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
			log.debug("Wiki HTTP GET failed: {}", e.getMessage());
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
			log.debug("Wiki HTTP request failed: {}", e.getMessage());
			return null;
		}
	}
}
