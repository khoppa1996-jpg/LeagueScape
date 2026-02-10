package com.leaguescape;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
	name = "LeagueScape"
)
public class LeagueScapePlugin extends Plugin
{
	private static final String STATE_GROUP = "leaguescapeState";
	private static final String KEY_UNLOCKED_AREAS = "unlockedAreas";

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private LeagueScapeConfig config;

	@Inject
	private com.leaguescape.area.AreaGraphService areaGraphService;

	@Inject
	private com.leaguescape.points.PointsService pointsService;

	@Inject
	private com.leaguescape.lock.LockEnforcer lockEnforcer;

	@Inject
	private EventBus eventBus;

	private NavigationButton navButton;

	@Override
	protected void startUp() throws Exception
	{
		log.info("LeagueScape started!");
		eventBus.register(lockEnforcer);
		pointsService.loadFromConfig();
		// Apply configured starting points when no persisted state exists (first run)
		if (pointsService.getEarnedTotal() == 0 && pointsService.getSpentTotal() == 0)
		{
			pointsService.setStartingPoints(config.startingPoints());
		}
		loadUnlockedAreas();
		LeagueScapePanel panel = new LeagueScapePanel(this, config, areaGraphService, pointsService);
		navButton = NavigationButton.builder()
			.tooltip("LeagueScape")
			.icon(panel.getIcon())
			.priority(70)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("LeagueScape stopped!");
		eventBus.unregister(lockEnforcer);
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}
	}

	@Provides
	LeagueScapeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LeagueScapeConfig.class);
	}

	@Provides
	@Singleton
	com.leaguescape.area.AreaGraphService provideAreaGraphService()
	{
		return new com.leaguescape.area.AreaGraphService();
	}

	@Provides
	@Singleton
	com.leaguescape.points.PointsService providePointsService(ConfigManager configManager)
	{
		return new com.leaguescape.points.PointsService(configManager);
	}

	@Provides
	com.leaguescape.lock.LockEnforcer provideLockEnforcer(Client client, com.leaguescape.area.AreaGraphService areaGraphService)
	{
		return new com.leaguescape.lock.LockEnforcer(client, areaGraphService);
	}

	private void loadUnlockedAreas()
	{
		String raw = configManager.getConfiguration(STATE_GROUP, KEY_UNLOCKED_AREAS);
		if (raw != null && !raw.isEmpty())
		{
			java.util.Set<String> set = new java.util.HashSet<>(java.util.Arrays.asList(raw.split(",")));
			areaGraphService.setUnlockedAreaIds(set);
		}
		else
		{
			String start = config.startingArea();
			if (start != null && !start.isEmpty())
			{
				areaGraphService.setUnlockedAreaIds(java.util.Collections.singleton(start));
				persistUnlockedAreas();
			}
		}
	}

	private void persistUnlockedAreas()
	{
		String joined = String.join(",", areaGraphService.getUnlockedAreaIds());
		configManager.setConfiguration(STATE_GROUP, KEY_UNLOCKED_AREAS, joined);
	}

	/** Called by panel when user clicks unlock. Returns true if unlocked. */
	public boolean unlockArea(String areaId, int cost)
	{
		if (!pointsService.spend(cost)) return false;
		areaGraphService.addUnlocked(areaId);
		persistUnlockedAreas();
		return true;
	}
}
