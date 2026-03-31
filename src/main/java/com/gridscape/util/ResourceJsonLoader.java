package com.gridscape.util;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;

/**
 * Loads JSON from classpath resources with UTF-8 and optional logging.
 * Services that need custom Gson (e.g. custom adapters) pass their Gson and Type.
 */
public final class ResourceJsonLoader
{
	private ResourceJsonLoader() {}

	/**
	 * Opens a classpath resource as an input stream. Caller must close the stream.
	 * @param clazz class used to resolve the resource (e.g. YourService.class)
	 * @param resourcePath path with leading slash (e.g. "/world_unlocks.json")
	 * @return the stream, or null if resource not found
	 */
	public static InputStream openResource(Class<?> clazz, String resourcePath)
	{
		InputStream in = clazz.getResourceAsStream(resourcePath);
		return in;
	}

	/**
	 * Opens a classpath resource as a UTF-8 reader. Caller must close the reader.
	 * @param clazz class used to resolve the resource
	 * @param resourcePath path with leading slash
	 * @return the reader, or null if resource not found
	 */
	public static Reader openReader(Class<?> clazz, String resourcePath)
	{
		InputStream in = openResource(clazz, resourcePath);
		if (in == null)
			return null;
		return new InputStreamReader(in, StandardCharsets.UTF_8);
	}

	/**
	 * Loads and parses JSON from a classpath resource. Uses try-with-resources; returns null on missing resource or parse error.
	 * @param clazz class used to resolve the resource
	 * @param resourcePath path with leading slash
	 * @param type Gson type (e.g. MyClass.class or TypeToken.get())
	 * @param gson Gson instance (may have custom adapters)
	 * @return parsed object or null
	 */
	public static <T> T load(Class<?> clazz, String resourcePath, Type type, Gson gson)
	{
		return load(clazz, resourcePath, type, gson, null);
	}

	/**
	 * Like {@link #load(Class, String, Type, Gson)} but logs a warning when resource is missing and logs errors on parse failure.
	 */
	public static <T> T load(Class<?> clazz, String resourcePath, Type type, Gson gson, Logger log)
	{
		try (InputStream in = clazz.getResourceAsStream(resourcePath))
		{
			if (in == null)
			{
				if (log != null)
					log.warn("Resource not found: {}", resourcePath);
				return null;
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				return gson.fromJson(reader, type);
			}
		}
		catch (Exception e)
		{
			if (log != null)
				log.error("Failed to load " + resourcePath, e);
			return null;
		}
	}
}
