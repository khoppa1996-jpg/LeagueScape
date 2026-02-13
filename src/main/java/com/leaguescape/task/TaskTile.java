package com.leaguescape.task;

import java.util.List;
import lombok.Value;

/**
 * One tile in the task grid for an area. Immutable.
 * id is e.g. "0,0" for center; tier 0 = center (free), tier 1+ = rings.
 * requiredAreaIds: when non-empty, task is shown as "mystery" until all these areas are unlocked.
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
	/** When non-null and non-empty, task appears in each listed area and is mystery until all are unlocked. */
	List<String> requiredAreaIds;

	/** Create tile with no task type (icon falls back to display name). */
	public static TaskTile of(String id, int tier, String displayName, int points, int row, int col)
	{
		return new TaskTile(id, tier, displayName, points, row, col, null, null);
	}

	public static String idFor(int row, int col)
	{
		return row + "," + col;
	}

	/** True if this task is mystery (required areas not all unlocked yet). */
	public boolean isMystery(java.util.Set<String> unlockedAreaIds)
	{
		if (requiredAreaIds == null || requiredAreaIds.isEmpty()) return false;
		return !unlockedAreaIds.containsAll(requiredAreaIds);
	}
}
