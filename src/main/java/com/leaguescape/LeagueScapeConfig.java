package com.leaguescape;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

/**
 * LeagueScape configuration: overlay appearance (locked overlay, boundary lines, colors), map
 * overlay options, progression (starting area, points, unlock mode), and task system (task mode
 * F2P/Members, difficulty multiplier, points per tier, tasks file path). Used by the plugin and
 * config panel; values are persisted by RuneLite's config system.
 */
@ConfigGroup("leaguescape")
public interface LeagueScapeConfig extends Config
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
		name = "Resetting progress",
		description = "Reset all LeagueScape progress (points, area unlocks, task completions). Use the Reset Progress button on the LeagueScape sidebar panel (LeagueScape icon in the sidebar).",
		position = 4
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

	@Alpha
	@ConfigItem(
		keyName = "lockedOverlayColor",
		name = "Locked overlay color",
		description = "Color of the locked area overlay",
		position = 1,
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
		position = 2,
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
		position = 3,
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
		position = 4,
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
		position = 5,
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
		description = "Point buy = spend points to unlock areas. Points to complete = earn points in each area to complete it, then spend points to unlock the next area.",
		section = progressionSection
	)
	default UnlockMode unlockMode()
	{
		return UnlockMode.POINT_BUY;
	}

	enum UnlockMode
	{
		POINT_BUY("Point buy"),
		POINTS_TO_COMPLETE("Points to complete");

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
		keyName = "taskTier1Points",
		name = "Tier 1 points",
		description = "Points awarded for claiming a tier 1 task (first ring)",
		position = 1,
		section = taskSection
	)
	default int taskTier1Points()
	{
		return 1;
	}

	@ConfigItem(
		keyName = "taskTier2Points",
		name = "Tier 2 points",
		description = "Points awarded for claiming a tier 2 task",
		position = 2,
		section = taskSection
	)
	default int taskTier2Points()
	{
		return 2;
	}

	@ConfigItem(
		keyName = "taskTier3Points",
		name = "Tier 3 points",
		description = "Points awarded for claiming a tier 3 task",
		position = 3,
		section = taskSection
	)
	default int taskTier3Points()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "taskTier4Points",
		name = "Tier 4 points",
		description = "Points awarded for claiming a tier 4 task",
		position = 4,
		section = taskSection
	)
	default int taskTier4Points()
	{
		return 4;
	}

	@ConfigItem(
		keyName = "taskTier5Points",
		name = "Tier 5 points",
		description = "Points awarded for claiming a tier 5 task",
		position = 5,
		section = taskSection
	)
	default int taskTier5Points()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "tasksFilePath",
		name = "Tasks file path",
		description = "Optional path to a tasks.json file. If empty, the built-in default tasks are used. File format: defaultTasks array with displayName, taskType, difficulty (1–5); optional areas map for per-area overrides.",
		position = 6,
		section = taskSection
	)
	default String tasksFilePath()
	{
		return "";
	}
}
