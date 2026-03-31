package com.gridscape.util;

/**
 * Config and state group names used by GridScape for RuneLite config persistence.
 * Use these instead of string literals to avoid typos and simplify renames.
 */
public final class GridScapeConfigConstants
{
	private GridScapeConfigConstants() {}

	/** Config group for main plugin config (GridScapeConfig). */
	public static final String CONFIG_GROUP = "gridscape";
	/** State group for persisted state (unlocked areas, task progress, world unlock, etc.). */
	public static final String STATE_GROUP = "gridscapeState";
	/** Config group for custom/removed areas (AreaGraphService). */
	public static final String CONFIG_GROUP_CUSTOM_AREAS = "gridscapeConfig";
}
