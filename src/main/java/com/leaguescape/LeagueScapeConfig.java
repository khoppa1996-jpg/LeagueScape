package com.leaguescape;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("leaguescape")
public interface LeagueScapeConfig extends Config
{
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
