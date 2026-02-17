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

	/**
	 * Creates a task tile with no task type (icon lookup will fall back to display name).
	 *
	 * @param id         unique tile id (e.g. "0,0")
	 * @param tier       ring tier (0 = center, 1+ = outer rings)
	 * @param displayName text shown to the player
	 * @param points     points awarded when claimed
	 * @param row        grid row (for layout)
	 * @param col        grid column (for layout)
	 * @return new TaskTile with taskType and requiredAreaIds null
	 */
	public static TaskTile of(String id, int tier, String displayName, int points, int row, int col)
	{
		return new TaskTile(id, tier, displayName, points, row, col, null, null);
	}

	/**
	 * Builds the standard tile ID string from grid coordinates (used for persistence and lookup).
	 *
	 * @param row grid row
	 * @param col grid column
	 * @return id string "row,col"
	 */
	public static String idFor(int row, int col)
	{
		return row + "," + col;
	}

	/**
	 * Returns true if this task should be shown as a mystery (question mark) because not all
	 * required areas are unlocked yet. When requiredAreaIds is empty, the task is never a mystery.
	 *
	 * @param unlockedAreaIds set of area IDs the player has unlocked
	 * @return true if requiredAreaIds is non-empty and at least one required area is not in unlockedAreaIds
	 */
	public boolean isMystery(java.util.Set<String> unlockedAreaIds)
	{
		if (requiredAreaIds == null || requiredAreaIds.isEmpty()) return false;
		return !unlockedAreaIds.containsAll(requiredAreaIds);
	}
}
