package com.leaguescape;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "LeagueScape",
	enabledByDefault = true
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
	private com.leaguescape.overlay.LockedRegionOverlay lockedRegionOverlay;

	@Inject
	private com.leaguescape.overlay.LeagueScapeMapOverlay leagueScapeMapOverlay;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private Client client;

	private NavigationButton navButton;
	private boolean mapMouseListenerRegistered;

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
		overlayManager.add(lockedRegionOverlay);
		overlayManager.add(leagueScapeMapOverlay);
		eventBus.register(this);
		updateMapMouseListener();
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
		eventBus.unregister(this);
		if (mapMouseListenerRegistered)
		{
			mouseManager.unregisterMouseListener(leagueScapeMapOverlay);
			mapMouseListenerRegistered = false;
		}
		overlayManager.remove(lockedRegionOverlay);
		overlayManager.remove(leagueScapeMapOverlay);
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
	com.leaguescape.points.PointsService providePointsService(ConfigManager configManager)
	{
		return new com.leaguescape.points.PointsService(configManager);
	}

	@Provides
	com.leaguescape.lock.LockEnforcer provideLockEnforcer(Client client, com.leaguescape.area.AreaGraphService areaGraphService)
	{
		return new com.leaguescape.lock.LockEnforcer(client, areaGraphService);
	}

	@Provides
	com.leaguescape.overlay.LockedRegionOverlay provideLockedRegionOverlay(Client client, com.leaguescape.area.AreaGraphService areaGraphService, LeagueScapeConfig config)
	{
		return new com.leaguescape.overlay.LockedRegionOverlay(client, areaGraphService, config);
	}

	@Provides
	com.leaguescape.overlay.LeagueScapeMapOverlay provideLeagueScapeMapOverlay(Client client, com.leaguescape.area.AreaGraphService areaGraphService,
		LeagueScapeConfig config, com.leaguescape.points.PointsService pointsService)
	{
		return new com.leaguescape.overlay.LeagueScapeMapOverlay(client, areaGraphService, config, pointsService, this);
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

	@Subscribe
	public void onGameTick(GameTick event)
	{
		updateMapMouseListener();
	}

	private void updateMapMouseListener()
	{
		Widget mapContainer = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		boolean mapOpen = mapContainer != null && !mapContainer.isHidden();
		if (mapOpen && !mapMouseListenerRegistered)
		{
			mouseManager.registerMouseListener(leagueScapeMapOverlay);
			mapMouseListenerRegistered = true;
		}
		else if (!mapOpen && mapMouseListenerRegistered)
		{
			mouseManager.unregisterMouseListener(leagueScapeMapOverlay);
			mapMouseListenerRegistered = false;
		}
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
