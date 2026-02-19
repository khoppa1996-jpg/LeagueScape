package com.leaguescape.task;

import java.util.List;
import lombok.Value;

/**
 * One tile in the task grid for an area. Immutable.
 * id is e.g. "0,0" for center; tier 0 = center (free), tier 1+ = rings.
 * requiredAreaIds: when non-empty, task is shown as "mystery" until visibility condition is met.
 * requireAllAreas: when true, mystery until all required areas unlocked; when false (any), mystery only until this area is unlocked.
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
	/** When non-null and non-empty, task appears in each listed area; mystery logic depends on requireAllAreas. */
	List<String> requiredAreaIds;
	/** True = mystery until all required areas unlocked; false = mystery only until current area unlocked (either/or task). */
	boolean requireAllAreas;
	/** Optional quest requirements (e.g. "Waterfall Quest" or "Another Slice of H.A.M., Giant Dwarf"); must be complete before task can be claimed. */
	String requirements;

	/**
	 * Creates a task tile with no task type (icon lookup will fall back to display name).
	 *
	 * @param id         unique tile id (e.g. "0,0")
	 * @param tier       ring tier (0 = center, 1+ = outer rings)
	 * @param displayName text shown to the player
	 * @param points     points awarded when claimed
	 * @param row        grid row (for layout)
	 * @param col        grid column (for layout)
	 * @return new TaskTile with taskType and requiredAreaIds null, requireAllAreas true
	 */
	public static TaskTile of(String id, int tier, String displayName, int points, int row, int col)
	{
		return new TaskTile(id, tier, displayName, points, row, col, null, null, true, null);
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
	 * Returns true if this task should be shown as a mystery (question mark).
	 * When requiredAreaIds is empty, the task is never a mystery.
	 * When requireAllAreas is true: mystery until all required areas are unlocked.
	 * When requireAllAreas is false (either/or): mystery only until currentAreaId is unlocked; pass the area this grid is for.
	 *
	 * @param unlockedAreaIds set of area IDs the player has unlocked
	 * @param currentAreaId   area ID this grid is for (required when requireAllAreas is false)
	 * @return true if the task should be shown as a mystery
	 */
	public boolean isMystery(java.util.Set<String> unlockedAreaIds, String currentAreaId)
	{
		if (requiredAreaIds == null || requiredAreaIds.isEmpty()) return false;
		if (!requireAllAreas && currentAreaId != null)
			return requiredAreaIds.contains(currentAreaId) && !unlockedAreaIds.contains(currentAreaId);
		return !unlockedAreaIds.containsAll(requiredAreaIds);
	}

	/** Legacy: same as isMystery(unlockedAreaIds, null), i.e. requireAllAreas behavior. */
	public boolean isMystery(java.util.Set<String> unlockedAreaIds)
	{
		return isMystery(unlockedAreaIds, null);
	}
}
