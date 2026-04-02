package com.gridscape;

import javax.inject.Provider;
import net.runelite.api.Client;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;

/**
 * Static factories for Guice {@code @Provides} methods on {@link GridScapePlugin} (keeps the plugin class smaller).
 */
public final class GridScapeGuiceModule
{
	private GridScapeGuiceModule()
	{
	}

	public static GridScapeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GridScapeConfig.class);
	}

	public static com.gridscape.points.PointsService providePointsService(ConfigManager configManager)
	{
		return new com.gridscape.points.PointsService(configManager);
	}

	public static com.gridscape.points.AreaCompletionService provideAreaCompletionService(ConfigManager configManager,
		com.gridscape.area.AreaGraphService areaGraphService, com.gridscape.points.PointsService pointsService,
		GridScapeConfig config, Provider<com.gridscape.task.TaskGridService> taskGridServiceProvider)
	{
		return new com.gridscape.points.AreaCompletionService(configManager, areaGraphService, pointsService, config, taskGridServiceProvider);
	}

	public static com.gridscape.lock.LockEnforcer provideLockEnforcer(Client client, GridScapeConfig config, com.gridscape.area.AreaGraphService areaGraphService)
	{
		return new com.gridscape.lock.LockEnforcer(client, config, areaGraphService);
	}

	public static com.gridscape.overlay.LockedRegionOverlay provideLockedRegionOverlay(Client client, com.gridscape.area.AreaGraphService areaGraphService, GridScapeConfig config)
	{
		return new com.gridscape.overlay.LockedRegionOverlay(client, areaGraphService, config);
	}

	public static com.gridscape.overlay.TaskCompletionPopupOverlay provideTaskCompletionPopupOverlay(Client client)
	{
		return new com.gridscape.overlay.TaskCompletionPopupOverlay(client);
	}

	public static com.gridscape.wiki.OsrsWikiApiService provideOsrsWikiApiService()
	{
		return new com.gridscape.wiki.OsrsWikiApiService();
	}

	public static com.gridscape.wiki.WikiTaskGenerator provideWikiTaskGenerator(com.gridscape.wiki.OsrsWikiApiService osrsWikiApiService)
	{
		return new com.gridscape.wiki.WikiTaskGenerator(osrsWikiApiService);
	}

	public static com.gridscape.wiki.OsrsItemService provideOsrsItemService()
	{
		return new com.gridscape.wiki.OsrsItemService();
	}

	public static com.gridscape.task.TaskGridService provideTaskGridService(ConfigManager configManager, GridScapeConfig config,
		com.gridscape.points.PointsService pointsService,
		com.gridscape.points.AreaCompletionService areaCompletionService,
		com.gridscape.area.AreaGraphService areaGraphService,
		Client client)
	{
		return new com.gridscape.task.TaskGridService(configManager, config, pointsService, areaCompletionService, areaGraphService, client);
	}

	public static com.gridscape.overlay.GridScapeMapOverlay provideGridScapeMapOverlay(Client client, com.gridscape.area.AreaGraphService areaGraphService,
		GridScapeConfig config, com.gridscape.points.PointsService pointsService,
		com.gridscape.points.AreaCompletionService areaCompletionService,
		ConfigManager configManager,
		com.gridscape.task.TaskGridService taskGridService,
		com.gridscape.worldunlock.WorldUnlockService worldUnlockService,
		com.gridscape.wiki.OsrsWikiApiService osrsWikiApiService,
		AudioPlayer audioPlayer, ClientThread clientThread,
		GridScapePlugin plugin)
	{
		return new com.gridscape.overlay.GridScapeMapOverlay(client, areaGraphService, config, pointsService, areaCompletionService, plugin, configManager, taskGridService, worldUnlockService, osrsWikiApiService, audioPlayer, clientThread);
	}

	public static com.gridscape.config.AreaEditOverlay provideAreaEditOverlay(Client client, com.gridscape.area.AreaGraphService areaGraphService,
		Provider<GridScapePlugin> pluginProvider)
	{
		return new com.gridscape.config.AreaEditOverlay(client, areaGraphService, pluginProvider);
	}

	public static com.gridscape.worldunlock.WorldUnlockService provideWorldUnlockService(ConfigManager configManager,
		GridScapeConfig config,
		com.gridscape.points.PointsService pointsService,
		com.gridscape.task.TaskGridService taskGridService,
		com.gridscape.area.AreaGraphService areaGraphService)
	{
		return new com.gridscape.worldunlock.WorldUnlockService(configManager, config, pointsService, taskGridService, areaGraphService);
	}

	public static com.gridscape.worldunlock.GlobalTaskListService provideGlobalTaskListService(ConfigManager configManager,
		GridScapeConfig config,
		com.gridscape.points.PointsService pointsService,
		com.gridscape.worldunlock.WorldUnlockService worldUnlockService,
		com.gridscape.task.TaskGridService taskGridService)
	{
		return new com.gridscape.worldunlock.GlobalTaskListService(configManager, config, pointsService, worldUnlockService, taskGridService);
	}

	public static com.gridscape.worldunlock.GoalTrackingService provideGoalTrackingService()
	{
		return new com.gridscape.worldunlock.GoalTrackingService();
	}
}
