package com.gridscape;

import com.gridscape.util.GridScapeConfigConstants;
import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

/**
 * GridScape configuration: overlay appearance (locked overlay, boundary lines, colors), map
 * overlay options, progression (starting area, points, unlock mode), and task system (task mode
 * F2P/Members, difficulty multiplier, points per tier, tasks file path). Used by the plugin and
 * config panel; values are persisted by RuneLite's config system.
 */
@ConfigGroup(GridScapeConfigConstants.CONFIG_GROUP)
public interface GridScapeConfig extends Config
{
	@ConfigSection(
		name = "Overlay appearance",
		description = "Settings for the locked region overlay (scene and map)",
		position = 0
	)
	String overlaySection = "overlaySection";

	@ConfigSection(
		name = "Map overlay",
		description = "Settings for the world map overlay",
		position = 1
	)
	String mapSection = "mapSection";

	@ConfigSection(
		name = "Progression",
		description = "How areas are unlocked and how points work",
		position = 2
	)
	String progressionSection = "progressionSection";

	@ConfigSection(
		name = "Task system",
		description = "Task grid difficulty and points per tier",
		position = 3
	)
	String taskSection = "taskSection";

	@ConfigSection(
		name = "World Unlock",
		description = "Tile cost multipliers when unlock mode is World Unlock. Cost = tier × tier points × multiplier.",
		position = 4
	)
	String worldUnlockSection = "worldUnlockSection";

	@ConfigSection(
		name = "Resetting progress",
		description = "Reset all GridScape progress (points, area unlocks, task completions). Use the Reset Progress button in the Rules & Setup panel.",
		position = 5
	)
	String resetSection = "resetSection";

	// Overlay appearance (scene)

	@ConfigItem(
		keyName = "renderLockedOverlay",
		name = "Locked area overlay",
		description = "Draw grey overlay on locked tiles (approximates region-locker shader)",
		position = 0,
		section = overlaySection
	)
	default boolean renderLockedOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "strictLockEnforcement",
		name = "Strict lock enforcement",
		description = "When ON: block all interactions through the overlay (clicking locked areas does nothing). When OFF: overlay is still shown but you can interact through it (all interactions allowed).",
		position = 1,
		section = overlaySection
	)
	default boolean strictLockEnforcement()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "lockedOverlayColor",
		name = "Locked overlay color",
		description = "Color of the locked area overlay",
		position = 2,
		section = overlaySection
	)
	default Color lockedOverlayColor()
	{
		return new Color(80, 80, 80, 90);
	}

	@ConfigItem(
		keyName = "renderPolygonBoundaries",
		name = "Draw area boundary lines",
		description = "Draw corner-to-corner lines between locked and unlocked areas (hidden when both neighbors unlocked)",
		position = 3,
		section = overlaySection
	)
	default boolean renderPolygonBoundaries()
	{
		return true;
	}

	@ConfigItem(
		keyName = "renderRegionBorders",
		name = "Draw chunk borders",
		description = "Draw 64x64 chunk boundary lines (like region-locker)",
		position = 4,
		section = overlaySection
	)
	default boolean renderRegionBorders()
	{
		return false;
	}

	@ConfigItem(
		keyName = "regionBorderWidth",
		name = "Chunk/boundary line width",
		description = "Width of the chunk borders and area boundary lines",
		position = 5,
		section = overlaySection
	)
	default int regionBorderWidth()
	{
		return 1;
	}

	@Alpha
	@ConfigItem(
		keyName = "regionBorderColor",
		name = "Chunk/boundary line color",
		description = "Color of the chunk borders and area boundary lines",
		position = 6,
		section = overlaySection
	)
	default Color regionBorderColor()
	{
		return new Color(0, 200, 83, 200);
	}

	// Map overlay

	@ConfigItem(
		keyName = "drawMapOverlay",
		name = "Draw areas on map",
		description = "Draw locked/unlocked areas on the world map when open",
		position = 10,
		section = mapSection
	)
	default boolean drawMapOverlay()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "mapLockedColor",
		name = "Locked area color (map)",
		description = "Color for locked areas on the world map",
		position = 11,
		section = mapSection
	)
	default Color mapLockedColor()
	{
		return new Color(200, 16, 0, 100);
	}

	@Alpha
	@ConfigItem(
		keyName = "mapUnlockedColor",
		name = "Unlocked area color (map)",
		description = "Color for unlocked areas on the world map",
		position = 12,
		section = mapSection
	)
	default Color mapUnlockedColor()
	{
		return new Color(60, 200, 160, 100);
	}

	@Alpha
	@ConfigItem(
		keyName = "mapUnlockableColor",
		name = "Unlockable area color (map)",
		description = "Color for unlockable (neighbor) areas on the world map",
		position = 13,
		section = mapSection
	)
	default Color mapUnlockableColor()
	{
		return new Color(255, 200, 0, 100);
	}

	@ConfigItem(
		keyName = "drawMapGrid",
		name = "Draw map grid",
		description = "Draw chunk grid on the world map",
		position = 14,
		section = mapSection
	)
	default boolean drawMapGrid()
	{
		return true;
	}

	@ConfigItem(
		keyName = "drawAreaLabels",
		name = "Draw area labels",
		description = "Draw area names on the world map",
		position = 15,
		section = mapSection
	)
	default boolean drawAreaLabels()
	{
		return true;
	}

	@ConfigItem(
		keyName = "drawAreaCornersOnMap",
		name = "Show area corners on map",
		description = "Draw polygon corner markers on the world map (hovered area, or area being edited)",
		position = 16,
		section = mapSection
	)
	default boolean drawAreaCornersOnMap()
	{
		return false;
	}

	@ConfigItem(
		keyName = "startingArea",
		name = "Starting area",
		description = "The area or city you start in (pick on map or choose from dropdown)",
		section = progressionSection
	)
	default String startingArea()
	{
		return "lumbridge";
	}

	@ConfigItem(
		keyName = "startingPoints",
		name = "Starting points",
		description = "Number of points you begin with",
		section = progressionSection
	)
	default int startingPoints()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "unlockMode",
		name = "Unlock mode",
		description = "Point buy = spend points to unlock areas. Points to complete = earn points in each area, then spend to unlock next. World Unlock = spend points on a grid of tiles (skills, quests, diaries, bosses, areas); tasks come from unlocked tiles.",
		section = progressionSection
	)
	default UnlockMode unlockMode()
	{
		return UnlockMode.WORLD_UNLOCK;
	}

	enum UnlockMode
	{
		POINT_BUY("Point buy"),
		POINTS_TO_COMPLETE("Points to complete"),
		WORLD_UNLOCK("World Unlock");

		private final String label;

		UnlockMode(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum TaskMode
	{
		MEMBERS("Members"),
		FREE_TO_PLAY("Free to Play");

		private final String label;

		TaskMode(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	// Task system

	@ConfigItem(
		keyName = "taskMode",
		name = "Task mode",
		description = "Free to Play: only tasks marked F2P are available. Members: all tasks (including F2P) are available.",
		position = 0,
		section = taskSection
	)
	default TaskMode taskMode()
	{
		return TaskMode.MEMBERS;
	}

	@ConfigItem(
		keyName = "taskDifficultyMultiplier",
		name = "Task difficulty multiplier",
		description = "Scales task grid difficulty (0.5 = easier, 1 = normal, up to 2 = harder).",
		position = 1,
		section = taskSection
	)
	default double taskDifficultyMultiplier()
	{
		return 1.0;
	}

	@ConfigItem(
		keyName = "taskTier1Points",
		name = "Tier 1 points",
		description = "Points awarded for claiming a tier 1 task (first ring)",
		position = 2,
		section = taskSection
	)
	default int taskTier1Points()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "taskTier2Points",
		name = "Tier 2 points",
		description = "Points awarded for claiming a tier 2 task",
		position = 3,
		section = taskSection
	)
	default int taskTier2Points()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "taskTier3Points",
		name = "Tier 3 points",
		description = "Points awarded for claiming a tier 3 task",
		position = 4,
		section = taskSection
	)
	default int taskTier3Points()
	{
		return 20;
	}

	@ConfigItem(
		keyName = "taskTier4Points",
		name = "Tier 4 points",
		description = "Points awarded for claiming a tier 4 task",
		position = 5,
		section = taskSection
	)
	default int taskTier4Points()
	{
		return 50;
	}

	@ConfigItem(
		keyName = "taskTier5Points",
		name = "Tier 5 points",
		description = "Points awarded for claiming a tier 5 task",
		position = 6,
		section = taskSection
	)
	default int taskTier5Points()
	{
		return 100;
	}

	// World Unlock: per tile-tier (1–5) and unlock type. Tile tier > 5 uses tier 5 multipliers.

	@ConfigItem(keyName = "worldUnlockTier1SkillMultiplier", name = "Tier 1 skill multiplier", description = "Skill tile cost multiplier for tier 1 unlock tiles.", position = 0, section = worldUnlockSection)
	default int worldUnlockTier1SkillMultiplier() { return 3; }

	@ConfigItem(keyName = "worldUnlockTier1AreaMultiplier", name = "Tier 1 area multiplier", description = "Area tile cost multiplier for tier 1 unlock tiles.", position = 1, section = worldUnlockSection)
	default int worldUnlockTier1AreaMultiplier() { return 15; }

	@ConfigItem(keyName = "worldUnlockTier1BossMultiplier", name = "Tier 1 boss multiplier", description = "Boss tile cost multiplier for tier 1 unlock tiles.", position = 2, section = worldUnlockSection)
	default int worldUnlockTier1BossMultiplier() { return 10; }

	@ConfigItem(keyName = "worldUnlockTier1QuestMultiplier", name = "Tier 1 quest multiplier", description = "Quest tile cost multiplier for tier 1 unlock tiles.", position = 3, section = worldUnlockSection)
	default int worldUnlockTier1QuestMultiplier() { return 4; }

	@ConfigItem(keyName = "worldUnlockTier1AchievementDiaryMultiplier", name = "Tier 1 achievement diary multiplier", description = "Achievement diary tile cost multiplier for tier 1 unlock tiles.", position = 4, section = worldUnlockSection)
	default int worldUnlockTier1AchievementDiaryMultiplier() { return 4; }

	@ConfigItem(keyName = "worldUnlockTier2SkillMultiplier", name = "Tier 2 skill multiplier", description = "Skill tile cost multiplier for tier 2 unlock tiles.", position = 5, section = worldUnlockSection)
	default int worldUnlockTier2SkillMultiplier() { return 3; }

	@ConfigItem(keyName = "worldUnlockTier2AreaMultiplier", name = "Tier 2 area multiplier", description = "Area tile cost multiplier for tier 2 unlock tiles.", position = 6, section = worldUnlockSection)
	default int worldUnlockTier2AreaMultiplier() { return 10; }

	@ConfigItem(keyName = "worldUnlockTier2BossMultiplier", name = "Tier 2 boss multiplier", description = "Boss tile cost multiplier for tier 2 unlock tiles.", position = 7, section = worldUnlockSection)
	default int worldUnlockTier2BossMultiplier() { return 5; }

	@ConfigItem(keyName = "worldUnlockTier2QuestMultiplier", name = "Tier 2 quest multiplier", description = "Quest tile cost multiplier for tier 2 unlock tiles.", position = 8, section = worldUnlockSection)
	default int worldUnlockTier2QuestMultiplier() { return 4; }

	@ConfigItem(keyName = "worldUnlockTier2AchievementDiaryMultiplier", name = "Tier 2 achievement diary multiplier", description = "Achievement diary tile cost multiplier for tier 2 unlock tiles.", position = 9, section = worldUnlockSection)
	default int worldUnlockTier2AchievementDiaryMultiplier() { return 4; }

	@ConfigItem(keyName = "worldUnlockTier3SkillMultiplier", name = "Tier 3 skill multiplier", description = "Skill tile cost multiplier for tier 3 unlock tiles.", position = 10, section = worldUnlockSection)
	default int worldUnlockTier3SkillMultiplier() { return 2; }

	@ConfigItem(keyName = "worldUnlockTier3AreaMultiplier", name = "Tier 3 area multiplier", description = "Area tile cost multiplier for tier 3 unlock tiles.", position = 11, section = worldUnlockSection)
	default int worldUnlockTier3AreaMultiplier() { return 5; }

	@ConfigItem(keyName = "worldUnlockTier3BossMultiplier", name = "Tier 3 boss multiplier", description = "Boss tile cost multiplier for tier 3 unlock tiles.", position = 12, section = worldUnlockSection)
	default int worldUnlockTier3BossMultiplier() { return 3; }

	@ConfigItem(keyName = "worldUnlockTier3QuestMultiplier", name = "Tier 3 quest multiplier", description = "Quest tile cost multiplier for tier 3 unlock tiles.", position = 13, section = worldUnlockSection)
	default int worldUnlockTier3QuestMultiplier() { return 2; }

	@ConfigItem(keyName = "worldUnlockTier3AchievementDiaryMultiplier", name = "Tier 3 achievement diary multiplier", description = "Achievement diary tile cost multiplier for tier 3 unlock tiles.", position = 14, section = worldUnlockSection)
	default int worldUnlockTier3AchievementDiaryMultiplier() { return 2; }

	@ConfigItem(keyName = "worldUnlockTier4SkillMultiplier", name = "Tier 4 skill multiplier", description = "Skill tile cost multiplier for tier 4 unlock tiles.", position = 15, section = worldUnlockSection)
	default int worldUnlockTier4SkillMultiplier() { return 2; }

	@ConfigItem(keyName = "worldUnlockTier4AreaMultiplier", name = "Tier 4 area multiplier", description = "Area tile cost multiplier for tier 4 unlock tiles.", position = 16, section = worldUnlockSection)
	default int worldUnlockTier4AreaMultiplier() { return 4; }

	@ConfigItem(keyName = "worldUnlockTier4BossMultiplier", name = "Tier 4 boss multiplier", description = "Boss tile cost multiplier for tier 4 unlock tiles.", position = 17, section = worldUnlockSection)
	default int worldUnlockTier4BossMultiplier() { return 4; }

	@ConfigItem(keyName = "worldUnlockTier4QuestMultiplier", name = "Tier 4 quest multiplier", description = "Quest tile cost multiplier for tier 4 unlock tiles.", position = 18, section = worldUnlockSection)
	default int worldUnlockTier4QuestMultiplier() { return 2; }

	@ConfigItem(keyName = "worldUnlockTier4AchievementDiaryMultiplier", name = "Tier 4 achievement diary multiplier", description = "Achievement diary tile cost multiplier for tier 4 unlock tiles.", position = 19, section = worldUnlockSection)
	default int worldUnlockTier4AchievementDiaryMultiplier() { return 2; }

	@ConfigItem(keyName = "worldUnlockTier5SkillMultiplier", name = "Tier 5 skill multiplier", description = "Skill tile cost multiplier for tier 5+ unlock tiles.", position = 20, section = worldUnlockSection)
	default int worldUnlockTier5SkillMultiplier() { return 1; }

	@ConfigItem(keyName = "worldUnlockTier5AreaMultiplier", name = "Tier 5 area multiplier", description = "Area tile cost multiplier for tier 5+ unlock tiles.", position = 21, section = worldUnlockSection)
	default int worldUnlockTier5AreaMultiplier() { return 2; }

	@ConfigItem(keyName = "worldUnlockTier5BossMultiplier", name = "Tier 5 boss multiplier", description = "Boss tile cost multiplier for tier 5+ unlock tiles.", position = 22, section = worldUnlockSection)
	default int worldUnlockTier5BossMultiplier() { return 2; }

	@ConfigItem(keyName = "worldUnlockTier5QuestMultiplier", name = "Tier 5 quest multiplier", description = "Quest tile cost multiplier for tier 5+ unlock tiles.", position = 23, section = worldUnlockSection)
	default int worldUnlockTier5QuestMultiplier() { return 1; }

	@ConfigItem(keyName = "worldUnlockTier5AchievementDiaryMultiplier", name = "Tier 5 achievement diary multiplier", description = "Achievement diary tile cost multiplier for tier 5+ unlock tiles.", position = 24, section = worldUnlockSection)
	default int worldUnlockTier5AchievementDiaryMultiplier() { return 1; }

	@ConfigItem(
		keyName = "tasksFilePath",
		name = "Tasks file path",
		description = "Optional path to a tasks.json file. If empty, the built-in default tasks are used. File format: defaultTasks array with displayName, taskType, difficulty (1–5); optional areas map for per-area overrides.",
		position = 7,
		section = taskSection
	)
	default String tasksFilePath()
	{
		return "";
	}
}
