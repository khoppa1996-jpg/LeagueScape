package com.gridscape.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Helpers for parsing config values (e.g. comma-separated lists/sets).
 */
public final class ConfigParsing
{
	private ConfigParsing() {}

	/**
	 * Parses a comma-separated string into a set of trimmed, non-empty tokens.
	 * Null or empty input returns an empty set.
	 */
	public static Set<String> parseCommaSeparatedSet(String raw)
	{
		if (raw == null || raw.isEmpty())
			return new HashSet<>();
		Set<String> out = new HashSet<>();
		for (String part : raw.split(","))
		{
			String t = part.trim();
			if (!t.isEmpty())
				out.add(t);
		}
		return out;
	}

	/**
	 * Parses a comma-separated string into a list of trimmed, non-empty tokens (order preserved).
	 * Null or empty input returns an empty list.
	 */
	public static List<String> parseCommaSeparatedList(String raw)
	{
		if (raw == null || raw.isEmpty())
			return new ArrayList<>();
		List<String> out = new ArrayList<>();
		for (String part : raw.split(","))
		{
			String t = part.trim();
			if (!t.isEmpty())
				out.add(t);
		}
		return out;
	}

	/**
	 * Joins a set of strings with comma (no spaces). Used for persisting back to config.
	 */
	public static String joinComma(Set<String> set)
	{
		if (set == null || set.isEmpty())
			return "";
		return String.join(",", set);
	}

	/**
	 * Joins a list of strings with comma (no spaces). Used for persisting back to config.
	 */
	public static String joinComma(List<String> list)
	{
		if (list == null || list.isEmpty())
			return "";
		return String.join(",", list);
	}
}
