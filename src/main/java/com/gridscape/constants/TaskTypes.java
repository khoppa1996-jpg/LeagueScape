package com.leaguescape.constants;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Single source of truth for task type strings used in task definitions, UI dropdowns, and checks.
 * Use these constants instead of hardcoding task type strings.
 */
public final class TaskTypes
{
	private TaskTypes() {}

	// --- Canonical display names (used in tasks.json and UI) ---
	public static final String QUEST = "Quest";
	public static final String ACHIEVEMENT_DIARY = "Achievement Diary";
	/** Alias for Achievement Diary; normalize to {@link #ACHIEVEMENT_DIARY} for storage. */
	public static final String DIARY = "Diary";
	public static final String CLUE_SCROLL = "Clue Scroll";
	public static final String COLLECTION_LOG = "Collection Log";
	public static final String KILL_COUNT = "killCount";
	public static final String COMBAT = "Combat";
	public static final String ACTIVITY = "Activity";
	public static final String OTHER = "Other";
	public static final String LEVEL = "Level";
	public static final String EQUIPMENT = "Equipment";
	public static final String CONSTRUCTION = "Construction";

	/** Task type presets for config dropdown and task creator (single source of truth). Order preserved for UI. */
	public static final String[] TASK_TYPE_PRESETS = {
		ACHIEVEMENT_DIARY, ACTIVITY, QUEST, OTHER, LEVEL, EQUIPMENT, COLLECTION_LOG, CLUE_SCROLL, COMBAT,
		"Agility", "Construction", "Cooking", "Crafting", "Farming", "Firemaking", "Fletching", "Fishing", "Herblore", "Hunter",
		"Magic", "Mining", "Prayer", "Runecraft", "Slayer", "Smithing", "Sailing", "Thieving", "Woodcutting"
	};

	/** Unmodifiable list view of {@link #TASK_TYPE_PRESETS}. */
	public static final List<String> TASK_TYPE_PRESETS_LIST = Collections.unmodifiableList(Arrays.asList(TASK_TYPE_PRESETS));

	/**
	 * Lowercase names: skills whose global tasks can reset on new area unlock in World Unlock mode (when not {@code onceOnly}).
	 */
	private static final Set<String> REPEATABLE_WORLD_UNLOCK_SKILL_TYPES_LOWER = new HashSet<>(Arrays.asList(
		"cooking", "crafting", "firemaking", "fletching", "fishing", "hunter",
		"mining", "prayer", "thieving", "woodcutting"));

	/** True if {@code taskType} is in the World Unlock repeatable-skill whitelist (case-insensitive). */
	public static boolean isRepeatableWorldUnlockSkillType(String taskType)
	{
		if (taskType == null) return false;
		String t = taskType.trim().toLowerCase();
		return !t.isEmpty() && REPEATABLE_WORLD_UNLOCK_SKILL_TYPES_LOWER.contains(t);
	}

	/**
	 * Normalizes task type for storage/display. Maps "Diary" and "Achievement diary" to canonical {@link #ACHIEVEMENT_DIARY}.
	 */
	public static String normalizeToCanonical(String taskType)
	{
		if (taskType == null || taskType.isEmpty()) return taskType;
		String t = taskType.trim();
		if (DIARY.equalsIgnoreCase(t) || "Achievement diary".equalsIgnoreCase(t)) return ACHIEVEMENT_DIARY;
		return t;
	}

	/** True if the task type is achievement diary (canonical or common alias). */
	public static boolean isAchievementDiaryType(String taskType)
	{
		if (taskType == null) return false;
		String t = taskType.trim();
		return ACHIEVEMENT_DIARY.equalsIgnoreCase(t) || DIARY.equalsIgnoreCase(t) || "Achievement diary".equalsIgnoreCase(t);
	}

	/** True if the task type is clue-related. */
	public static boolean isClueType(String taskType)
	{
		return taskType != null && (CLUE_SCROLL.equalsIgnoreCase(taskType) || taskType.toLowerCase().contains("clue"));
	}

	/** True if the task type is collection log. */
	public static boolean isCollectionLogType(String taskType)
	{
		return taskType != null && taskType.toLowerCase().contains("collection");
	}
}
