package com.gridscape.worldunlock;

/**
 * Config keys for persisted global task list state (see {@link GlobalTaskListService}).
 */
public final class GlobalTaskListStateKeys
{
	private GlobalTaskListStateKeys()
	{
	}

	public static final String KEY_GLOBAL_CLAIMED = "globalTaskProgress_claimed";
	public static final String KEY_GLOBAL_COMPLETED = "globalTaskProgress_completed";
	public static final String KEY_GLOBAL_CENTER_CLAIMED = "globalTaskProgress_centerClaimed";
	public static final String KEY_GLOBAL_TASK_POSITIONS = "globalTaskProgress_positions";
	public static final String KEY_GLOBAL_PSEUDO_CENTER = "globalTaskProgress_pseudoCenter";
	public static final String KEY_GLOBAL_LAST_VIEWED = "globalTaskProgress_lastViewed";
	public static final String KEY_GLOBAL_CLAIMED_POSITIONS = "globalTaskProgress_claimedPositions";
	public static final String KEY_GLOBAL_RING_BONUS = "globalTaskProgress_ringBonus";
	public static final String KEY_GLOBAL_LAYOUT_SEED = "globalTaskProgress_layoutSeed";
	public static final String KEY_GLOBAL_TASK_HUB_BOOKMARKS = "globalTaskProgress_taskHubBookmarks";
	public static final String KEY_GLOBAL_ELIGIBLE_SNAPSHOT = "globalTaskProgress_eligibleSnapshot";
}
