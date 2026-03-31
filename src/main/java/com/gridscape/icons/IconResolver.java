package com.gridscape.icons;

import com.gridscape.constants.TaskTypes;
import java.util.Map;

/** Resolves classpath resource paths for boss and task-type icons. */
public final class IconResolver
{
	private IconResolver() {}

	public static String resolveBossIconPath(String bossId)
	{
		if (bossId == null || bossId.trim().isEmpty()) return null;
		String id = bossId.trim();
		String filename = IconResources.BOSS_ICON_OVERRIDES.get(id);
		if (filename == null)
			filename = "game_icon_" + id.replace("_", "") + ".png";
		return IconResources.BOSS_ICONS_RESOURCE_PREFIX + filename;
	}

	/** Full classpath path for an area unlock tile id, or null if unmapped. */
	public static String resolveAreaIconPath(String areaId)
	{
		if (areaId == null || areaId.isEmpty()) return null;
		String id = areaId.trim();
		String filename = IconResources.AREA_ICON_FILENAME.get(id);
		if (filename == null)
		{
			for (Map.Entry<String, String> e : IconResources.AREA_ICON_FILENAME.entrySet())
			{
				if (e.getKey() != null && e.getKey().equalsIgnoreCase(id))
				{
					filename = e.getValue();
					break;
				}
			}
		}
		if (filename == null) return null;
		return IconResources.AREA_ICONS_RESOURCE_PREFIX + filename;
	}

	public static String resolveTaskTypeLocalIconPath(String taskType)
	{
		if (taskType == null) return null;
		String t = TaskTypes.normalizeToCanonical(taskType).trim();
		if (t.isEmpty()) return null;

		// Fast path: exact key (most tasks.json values match UI case)
		String fn = IconResources.TASK_TYPE_LOCAL_ICON.get(t);
		if (fn != null) return IconResources.TASK_ICONS_RESOURCE_PREFIX + fn;

		// Robust path: ignore-case match (covers tasks.json/user edits with different casing)
		for (Map.Entry<String, String> e : IconResources.TASK_TYPE_LOCAL_ICON.entrySet())
		{
			String key = e.getKey();
			if (key != null && key.equalsIgnoreCase(t))
				return IconResources.TASK_ICONS_RESOURCE_PREFIX + e.getValue();
		}
		return null;
	}

	/**
	 * Full path for a task tile: if {@code bossId} is set (and type is not Collection Log), boss icon; else task-type map;
	 * {@code killCount} without boss uses combat icon; then collection log / clue heuristics from type or display name.
	 * Collection Log tasks keep the collection log tile icon even when {@code bossId} identifies the entry's boss.
	 */
	public static String resolveTaskTileLocalIconPath(String taskType, String displayName, String bossId)
	{
		if (bossId != null && !bossId.trim().isEmpty() && !TaskTypes.isCollectionLogType(taskType))
		{
			String bossPath = resolveBossIconPath(bossId.trim());
			if (bossPath != null) return bossPath;
		}

		if (taskType != null)
		{
			String tt = taskType.trim();
			if (TaskTypes.KILL_COUNT.equalsIgnoreCase(tt))
			{
				String combat = resolveTaskTypeLocalIconPath(TaskTypes.COMBAT);
				if (combat != null) return combat;
			}
		}

		String byType = resolveTaskTypeLocalIconPath(taskType);
		if (byType != null) return byType;

		if (isCollectionLogHint(taskType, displayName))
			return IconResources.TASK_ICONS_RESOURCE_PREFIX + "Collection_log_detail.png";
		if (isClueHint(taskType, displayName))
			return IconResources.TASK_ICONS_RESOURCE_PREFIX + "Clue_scroll_v1.png";
		return null;
	}

	private static boolean isCollectionLogHint(String taskType, String displayName)
	{
		if (TaskTypes.isCollectionLogType(taskType)) return true;
		return displayName != null && displayName.toLowerCase().contains("collection log");
	}

	private static boolean isClueHint(String taskType, String displayName)
	{
		if (TaskTypes.isClueType(taskType)) return true;
		return displayName != null && displayName.toLowerCase().contains("clue scroll");
	}
}
