package com.leaguescape;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

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
		keyName = "startingArea",
		name = "Starting area",
		description = "The area or city you start in (pick on map or choose from dropdown)"
	)
	default String startingArea()
	{
		return "lumbridge";
	}

	@ConfigItem(
		keyName = "startingPoints",
		name = "Starting points",
		description = "Number of points you begin with"
	)
	default int startingPoints()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "taskDifficultyMultiplier",
		name = "Task difficulty",
		description = "Overall multiplier for task difficulty (1.0 = normal)"
	)
	default double taskDifficultyMultiplier()
	{
		return 1.0;
	}

	@ConfigItem(
		keyName = "areaCompletionRequired",
		name = "Area completion required",
		description = "Must complete all revealed tasks in current area scope before unlock buttons become enabled"
	)
	default boolean areaCompletionRequired()
	{
		return false;
	}

	@ConfigItem(
		keyName = "strictLockLevel",
		name = "Strict lock level",
		description = "Basic = block walk/click only; Advanced = also filter menu entries for locked targets"
	)
	default boolean strictLockLevel()
	{
		return false;
	}

	@ConfigItem(
		keyName = "sailingOceanNodes",
		name = "Sailing / ocean nodes",
		description = "Include ocean regions as unlock nodes (only if Sailing is in game)"
	)
	default boolean sailingOceanNodes()
	{
		return false;
	}
}
