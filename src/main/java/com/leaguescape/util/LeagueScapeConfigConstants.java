package com.leaguescape.util;

/**
 * Config and state group names used by LeagueScape for RuneLite config persistence.
 * Use these instead of string literals to avoid typos and simplify renames.
 */
public final class LeagueScapeConfigConstants
{
	private LeagueScapeConfigConstants() {}

	/** Config group for main plugin config (LeagueScapeConfig). */
	public static final String CONFIG_GROUP = "leaguescape";
	/** State group for persisted state (unlocked areas, task progress, world unlock, etc.). */
	public static final String STATE_GROUP = "leaguescapeState";
	/** Config group for custom/removed areas (AreaGraphService). */
	public static final String CONFIG_GROUP_CUSTOM_AREAS = "leaguescapeConfig";
}
