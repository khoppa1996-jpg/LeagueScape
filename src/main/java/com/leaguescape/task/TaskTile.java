package com.leaguescape.task;

import lombok.Value;

/**
 * One tile in the task grid for an area. Immutable.
 * id is e.g. "0,0" for center; tier 0 = center (free), tier 1+ = rings.
 */
@Value
public class TaskTile
{
	String id;
	int tier;
	String displayName;
	int points;
	int row;
	int col;
	/** Optional task type for icon lookup (e.g. "Combat", "Mining", "Quest"). */
	String taskType;

	/** Create tile with no task type (icon falls back to display name). */
	public static TaskTile of(String id, int tier, String displayName, int points, int row, int col)
	{
		return new TaskTile(id, tier, displayName, points, row, col, null);
	}

	public static String idFor(int row, int col)
	{
		return row + "," + col;
	}
}
