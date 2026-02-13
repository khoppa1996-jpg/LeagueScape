package com.leaguescape.wiki;

import lombok.Value;

/**
 * Minimal item data from osrsbox items-summary (in-game ID and name).
 * Use when building task lists or looking up items by ID.
 */
@Value
public class OsrsItemSummary
{
	int id;
	String name;
}
