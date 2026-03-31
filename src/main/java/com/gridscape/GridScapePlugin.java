package com.gridscape;

import com.google.inject.Provides;
import com.google.inject.Provider;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.input.MouseManager;
import com.gridscape.util.PanelBoundsStore;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * GridScape plugin: area-based progression with unlockable regions, task grids per area, and
 * point economy. Loads areas from areas.json (and custom/removed from config), tracks unlocked
 * areas and points, enforces locking of tiles in locked areas, and provides the world-map overlay
 * (area details, task grid popups) and side panel (points, unlock buttons, tasks). Config editing
 * (areas, tasks) is in the config panel; area polygon editing uses right-click "Add polygon corner"
 * and "Set new corner" on the world map.
 */
@PluginDescriptor(
	name = "GridScape",
	enabledByDefault = true
)
public class GridScapePlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(GridScapePlugin.class);
	/** Config group for persisted state (unlocked areas, task progress, etc.). */
	private static final String STATE_GROUP = com.gridscape.util.GridScapeConfigConstants.STATE_GROUP;
	private static final String KEY_MIGRATION_DONE = "migrationFromLeagueScapeDone";

	/** Registers Escape key to close the given window (dispose). Call after creating a JDialog/JFrame. */
	public static void registerEscapeToClose(java.awt.Window window)
	{
		com.gridscape.util.GridScapeSwingUtil.registerEscapeToClose(window);
	}
	private static final String KEY_UNLOCKED_AREAS = "unlockedAreas";
	/** Comma-separated list of usernames for which the Rules & Setup panel has been shown (first-time open). */
	private static final String KEY_SETUP_OPENED_ACCOUNTS = "setupOpenedAccounts";

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	/** At most one of each floating panel; bounds persisted in {@link com.gridscape.util.PanelBoundsStore}. */
	private volatile JDialog worldUnlockDialogRef;
	private volatile JDialog globalTasksDialogRef;
	private volatile JDialog goalsDialogRef;

	@Inject
	private GridScapeConfig config;

	@Inject
	private com.gridscape.area.AreaGraphService areaGraphService;

	@Inject
	private com.gridscape.points.PointsService pointsService;

	@Inject
	private com.gridscape.points.AreaCompletionService areaCompletionService;

	@Inject
	private com.gridscape.lock.LockEnforcer lockEnforcer;

	@Inject
	private com.gridscape.overlay.LockedRegionOverlay lockedRegionOverlay;

	@Inject
	private com.gridscape.overlay.TaskCompletionPopupOverlay taskCompletionPopupOverlay;

	@Inject
	private com.gridscape.overlay.GridScapeMapOverlay gridScapeMapOverlay;

	@Inject
	private com.gridscape.overlay.GridScapeMinimapButtonOverlay gridScapeMinimapButtonOverlay;

	@Inject
	private Provider<com.gridscape.config.AreaEditOverlay> areaEditOverlayProvider;

	@Inject
	private Provider<com.gridscape.task.TaskGridService> taskGridServiceProvider;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private Client client;

	@Inject
	private net.runelite.client.callback.ClientThread clientThread;

	@Inject
	private AudioPlayer audioPlayer;

	@Inject
	private com.gridscape.wiki.OsrsWikiApiService osrsWikiApiService;

	@Inject
	private com.gridscape.wiki.WikiTaskGenerator wikiTaskGenerator;

	@Inject
	private com.gridscape.worldunlock.WorldUnlockService worldUnlockService;

	@Inject
	private com.gridscape.worldunlock.GlobalTaskListService globalTaskListService;

	@Inject
	private com.gridscape.worldunlock.GoalTrackingService goalTrackingService;

	private NavigationButton navButton;
	private com.gridscape.config.AreaEditOverlay areaEditOverlay;
	private boolean mapMouseListenerRegistered;

	// --- Area config editing (merged from GridScape Config plugin) ---
	private static final String ADD_CORNER_OPTION = "Add polygon corner";
	private static final String ADD_CORNER_TARGET = "Tile";
	private static final String MOVE_CORNER_OPTION = "Move";
	private static final String SET_CORNER_OPTION = "Set new corner";
	private static final String CANCEL_MOVE_OPTION = "Cancel move";
	private final com.gridscape.config.AreaEditState areaEditState = new com.gridscape.config.AreaEditState();
	/** Called when corners change (from plugin thread). */
	private Consumer<List<int[]>> cornerUpdateCallback;
	/** Called when neighbors change (e.g. from map "Add neighbors" dialog). */
	private Consumer<List<String>> neighborUpdateCallback;

	@Override
	protected void startUp() throws Exception
	{
		log.info("GridScape started!");
		migrateLegacyConfigAndStateIfNeeded();
		eventBus.register(lockEnforcer);
		pointsService.loadFromConfig();
		areaCompletionService.loadFromConfig();
		// Apply configured starting points when no persisted state exists (first run)
		if (pointsService.getEarnedTotal() == 0 && pointsService.getSpentTotal() == 0)
		{
			pointsService.setStartingPoints(config.startingPoints());
		}
		loadUnlockedAreas();
		if (config.unlockMode() == GridScapeConfig.UnlockMode.WORLD_UNLOCK)
		{
			worldUnlockService.load();
		}
		overlayManager.add(lockedRegionOverlay);
		overlayManager.add(taskCompletionPopupOverlay);
		overlayManager.add(gridScapeMapOverlay);
		overlayManager.add(gridScapeMinimapButtonOverlay);
		mouseManager.registerMouseListener(gridScapeMinimapButtonOverlay);
		areaEditOverlay = areaEditOverlayProvider.get();
		overlayManager.add(areaEditOverlay);
		eventBus.register(this);
		// updateMapMouseListener() uses client (getWidget, isHidden) and must run on client thread; onGameTick will call it
		GridScapePanel panel = new GridScapePanel(this, config, configManager, areaGraphService, pointsService, areaCompletionService, audioPlayer, client);
		navButton = NavigationButton.builder()
			.tooltip("GridScape")
			.icon(panel.getIcon())
			.priority(70)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		// Open Rules & Setup panel for this account if first time (by username)
		clientThread.invokeLater(this::tryOpenSetupForFirstTime);
	}

	private void migrateLegacyConfigAndStateIfNeeded()
	{
		// Only run once per install. Use the *new* state group as the sentinel.
		String done = configManager.getConfiguration(com.gridscape.util.GridScapeConfigConstants.STATE_GROUP, KEY_MIGRATION_DONE);
		if ("true".equalsIgnoreCase(done))
		{
			return;
		}

		int copied = 0;
		copied += migrateGroupBestEffort(
			com.gridscape.util.LegacyLeagueScapeConfigConstants.CONFIG_GROUP,
			com.gridscape.util.GridScapeConfigConstants.CONFIG_GROUP,
			null);
		copied += migrateGroupBestEffort(
			com.gridscape.util.LegacyLeagueScapeConfigConstants.STATE_GROUP,
			com.gridscape.util.GridScapeConfigConstants.STATE_GROUP,
			null);
		copied += migrateGroupBestEffort(
			com.gridscape.util.LegacyLeagueScapeConfigConstants.CONFIG_GROUP_CUSTOM_AREAS,
			com.gridscape.util.GridScapeConfigConstants.CONFIG_GROUP_CUSTOM_AREAS,
			new String[] { "customAreas", "removedAreas" });

		// Mark done regardless to avoid repeated work; leaving old keys intact is intentional.
		configManager.setConfiguration(com.gridscape.util.GridScapeConfigConstants.STATE_GROUP, KEY_MIGRATION_DONE, "true");
		if (copied > 0)
		{
			log.info("Migrated {} legacy LeagueScape config/state entries to GridScape.", copied);
		}
	}

	/**
	 * Copies keys from {@code oldGroup} to {@code newGroup}. If we can enumerate keys from ConfigManager,
	 * we migrate everything (including dynamic keys like task progress per area). If not, we fall back
	 * to a provided allowlist.
	 */
	private int migrateGroupBestEffort(String oldGroup, String newGroup, String[] fallbackKeys)
	{
		List<String> keys = tryGetConfigurationKeys(oldGroup);
		if (keys == null || keys.isEmpty())
		{
			if (fallbackKeys == null || fallbackKeys.length == 0)
			{
				return 0;
			}
			keys = new ArrayList<>();
			Collections.addAll(keys, fallbackKeys);
		}

		int copied = 0;
		for (String key : keys)
		{
			if (key == null || key.isEmpty())
			{
				continue;
			}
			String existing = configManager.getConfiguration(newGroup, key);
			if (existing != null && !existing.isEmpty())
			{
				continue; // don't override GridScape user changes
			}
			String legacy = configManager.getConfiguration(oldGroup, key);
			if (legacy == null || legacy.isEmpty())
			{
				continue;
			}
			configManager.setConfiguration(newGroup, key, legacy);
			copied++;
		}
		return copied;
	}

	@SuppressWarnings("unchecked")
	private List<String> tryGetConfigurationKeys(String group)
	{
		// RuneLite ConfigManager exposes key enumeration in most versions; use reflection so we don't
		// hard-depend on a particular signature.
		try
		{
			Method m = configManager.getClass().getMethod("getConfigurationKeys", String.class);
			Object result = m.invoke(configManager, group);
			if (result instanceof Collection)
			{
				return new ArrayList<>((Collection<String>) result);
			}
		}
		catch (Exception ignored)
		{
			// ignore
		}

		// Some versions use getConfigurationKeys(String, String) to return full keys; try that too.
		try
		{
			Method m = configManager.getClass().getMethod("getConfigurationKeys", String.class, String.class);
			Object result = m.invoke(configManager, group, "");
			if (result instanceof Collection)
			{
				return new ArrayList<>((Collection<String>) result);
			}
		}
		catch (Exception ignored)
		{
			// ignore
		}

		return null;
	}

	/**
	 * Opens Rules and Setup once per RuneScape account the first time the plugin runs with that account.
	 * Called after startup and again when the client reaches {@link net.runelite.api.GameState#LOGGED_IN} so it still runs
	 * if the plugin was enabled before login (username may be unavailable until then).
	 */
	private void tryOpenSetupForFirstTime()
	{
		String username = client.getUsername();
		if (username == null || username.isEmpty())
			return;
		String raw = configManager.getConfiguration(STATE_GROUP, KEY_SETUP_OPENED_ACCOUNTS);
		java.util.Set<String> seen = com.gridscape.util.ConfigParsing.parseCommaSeparatedSet(raw);
		if (seen.contains(username))
			return;
		seen.add(username);
		configManager.setConfiguration(STATE_GROUP, KEY_SETUP_OPENED_ACCOUNTS, com.gridscape.util.ConfigParsing.joinComma(seen));
		openSetupDialog();
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("GridScape stopped!");
		stopAreaEditing();
		eventBus.unregister(this);
		if (mapMouseListenerRegistered)
		{
			mouseManager.unregisterMouseListener(gridScapeMapOverlay);
			mapMouseListenerRegistered = false;
		}
		overlayManager.remove(lockedRegionOverlay);
		overlayManager.remove(taskCompletionPopupOverlay);
		overlayManager.remove(gridScapeMapOverlay);
		overlayManager.remove(gridScapeMinimapButtonOverlay);
		mouseManager.unregisterMouseListener(gridScapeMinimapButtonOverlay);
		if (areaEditOverlay != null)
		{
			overlayManager.remove(areaEditOverlay);
			areaEditOverlay = null;
		}
		eventBus.unregister(lockEnforcer);
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}
	}

	@Provides
	GridScapeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GridScapeConfig.class);
	}

	@Provides
	@Singleton
	com.gridscape.points.PointsService providePointsService(ConfigManager configManager)
	{
		return new com.gridscape.points.PointsService(configManager);
	}

	@Provides
	@Singleton
	com.gridscape.points.AreaCompletionService provideAreaCompletionService(ConfigManager configManager,
		com.gridscape.area.AreaGraphService areaGraphService, com.gridscape.points.PointsService pointsService,
		GridScapeConfig config, javax.inject.Provider<com.gridscape.task.TaskGridService> taskGridServiceProvider)
	{
		return new com.gridscape.points.AreaCompletionService(configManager, areaGraphService, pointsService, config, taskGridServiceProvider);
	}

	@Provides
	com.gridscape.lock.LockEnforcer provideLockEnforcer(Client client, GridScapeConfig config, com.gridscape.area.AreaGraphService areaGraphService)
	{
		return new com.gridscape.lock.LockEnforcer(client, config, areaGraphService);
	}

	@Provides
	com.gridscape.overlay.LockedRegionOverlay provideLockedRegionOverlay(Client client, com.gridscape.area.AreaGraphService areaGraphService, GridScapeConfig config)
	{
		return new com.gridscape.overlay.LockedRegionOverlay(client, areaGraphService, config);
	}

	@Provides
	com.gridscape.overlay.TaskCompletionPopupOverlay provideTaskCompletionPopupOverlay(Client client)
	{
		return new com.gridscape.overlay.TaskCompletionPopupOverlay(client);
	}

	@Provides
	@Singleton
	com.gridscape.wiki.OsrsWikiApiService provideOsrsWikiApiService()
	{
		return new com.gridscape.wiki.OsrsWikiApiService();
	}

	@Provides
	@Singleton
	com.gridscape.wiki.WikiTaskGenerator provideWikiTaskGenerator(com.gridscape.wiki.OsrsWikiApiService osrsWikiApiService)
	{
		return new com.gridscape.wiki.WikiTaskGenerator(osrsWikiApiService);
	}

	@Provides
	@Singleton
	com.gridscape.wiki.OsrsItemService provideOsrsItemService()
	{
		return new com.gridscape.wiki.OsrsItemService();
	}

	@Provides
	@Singleton
	com.gridscape.task.TaskGridService provideTaskGridService(ConfigManager configManager, GridScapeConfig config,
		com.gridscape.points.PointsService pointsService,
		com.gridscape.points.AreaCompletionService areaCompletionService,
		com.gridscape.area.AreaGraphService areaGraphService,
		Client client)
	{
		return new com.gridscape.task.TaskGridService(configManager, config, pointsService, areaCompletionService, areaGraphService, client);
	}

	@Provides
	com.gridscape.overlay.GridScapeMapOverlay provideGridScapeMapOverlay(Client client, com.gridscape.area.AreaGraphService areaGraphService,
		GridScapeConfig config, com.gridscape.points.PointsService pointsService,
		com.gridscape.points.AreaCompletionService areaCompletionService,
		ConfigManager configManager,
		com.gridscape.task.TaskGridService taskGridService,
		com.gridscape.worldunlock.WorldUnlockService worldUnlockService,
		com.gridscape.wiki.OsrsWikiApiService osrsWikiApiService,
		AudioPlayer audioPlayer, net.runelite.client.callback.ClientThread clientThread)
	{
		return new com.gridscape.overlay.GridScapeMapOverlay(client, areaGraphService, config, pointsService, areaCompletionService, this, configManager, taskGridService, worldUnlockService, osrsWikiApiService, audioPlayer, clientThread);
	}

	@Provides
	com.gridscape.config.AreaEditOverlay provideAreaEditOverlay(Client client, com.gridscape.area.AreaGraphService areaGraphService,
		Provider<GridScapePlugin> pluginProvider)
	{
		return new com.gridscape.config.AreaEditOverlay(client, areaGraphService, pluginProvider);
	}

	@Provides
	@Singleton
	com.gridscape.worldunlock.WorldUnlockService provideWorldUnlockService(ConfigManager configManager,
		GridScapeConfig config,
		com.gridscape.points.PointsService pointsService,
		com.gridscape.task.TaskGridService taskGridService,
		com.gridscape.area.AreaGraphService areaGraphService)
	{
		return new com.gridscape.worldunlock.WorldUnlockService(configManager, config, pointsService, taskGridService, areaGraphService);
	}

	@Provides
	@Singleton
	com.gridscape.worldunlock.GlobalTaskListService provideGlobalTaskListService(ConfigManager configManager,
		GridScapeConfig config,
		com.gridscape.points.PointsService pointsService,
		com.gridscape.worldunlock.WorldUnlockService worldUnlockService,
		com.gridscape.task.TaskGridService taskGridService)
	{
		return new com.gridscape.worldunlock.GlobalTaskListService(configManager, config, pointsService, worldUnlockService, taskGridService);
	}

	@Provides
	@Singleton
	com.gridscape.worldunlock.GoalTrackingService provideGoalTrackingService()
	{
		return new com.gridscape.worldunlock.GoalTrackingService();
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
			// In World Unlock mode, starter stays locked until unlocked on the World Unlock grid
			if (config.unlockMode() == GridScapeConfig.UnlockMode.WORLD_UNLOCK)
			{
				areaGraphService.setUnlockedAreaIds(Collections.emptySet());
				persistUnlockedAreas();
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
	}

	private void persistUnlockedAreas()
	{
		String joined = String.join(",", areaGraphService.getUnlockedAreaIds());
		configManager.setConfiguration(STATE_GROUP, KEY_UNLOCKED_AREAS, joined);
	}

	/**
	 * Opens the GridScape Rules and Setup popup (moveable, resizable) with tabs: Rules, Game Mode,
	 * Area Configuration, Controls. Call from the main panel's "Rules & Setup" button.
	 */
	public void openSetupDialog()
	{
		SwingUtilities.invokeLater(() -> {
			java.awt.Frame owner = null;
			if (client.getCanvas() != null)
			{
				java.awt.Window w = SwingUtilities.windowForComponent(client.getCanvas());
				if (w instanceof java.awt.Frame)
					owner = (java.awt.Frame) w;
			}
			com.gridscape.config.GridScapeSetupFrame frame = new com.gridscape.config.GridScapeSetupFrame(
				owner, this, areaGraphService, taskGridServiceProvider.get(), configManager, config,
				pointsService, areaCompletionService, osrsWikiApiService, wikiTaskGenerator, client, audioPlayer);
			// Default size: height = 1/3 of RuneLite window (at least 400), width 520–700
			if (owner != null)
			{
				int ownerHeight = owner.getHeight();
				int ownerWidth = owner.getWidth();
				if (ownerHeight > 0 && ownerWidth > 0)
				{
					int h = Math.max(400, ownerHeight / 3);
					int w = Math.max(520, Math.min(ownerWidth, 700));
					frame.setSize(w, h);
				}
				frame.setLocationRelativeTo(owner);
			}
			frame.setVisible(true);
		});
	}

	/**
	 * Returns true if the player has any progress (unlocked areas, points earned/spent, or world unlock state).
	 * Used to decide whether to show confirmation before updating starting rules or resetting.
	 */
	public boolean hasProgress()
	{
		if (pointsService.getEarnedTotal() > 0 || pointsService.getSpentTotal() > 0)
			return true;
		if (areaGraphService.getUnlockedAreaIds().size() > 0)
			return true;
		if (config.unlockMode() == GridScapeConfig.UnlockMode.WORLD_UNLOCK && worldUnlockService.getUnlockedIds().size() > 0)
			return true;
		return false;
	}

	/**
	 * Resets all GridScape progress: points to 0, all area unlocks cleared, all task completions
	 * cleared, area completion state (points-to-complete mode) cleared, and task grids reshuffled.
	 * Does not remove custom areas or custom tasks.
	 */
	public void resetProgress()
	{
		pointsService.setStartingPoints(config.startingPoints());
		// World Unlock: no areas on the map until the player unlocks them on the grid (full reset).
		if (config.unlockMode() == GridScapeConfig.UnlockMode.WORLD_UNLOCK)
		{
			configManager.setConfiguration(STATE_GROUP, KEY_UNLOCKED_AREAS, "");
			areaGraphService.setUnlockedAreaIds(Collections.emptySet());
		}
		else
		{
			String start = config.startingArea();
			if (start != null && !start.isEmpty())
			{
				areaGraphService.setUnlockedAreaIds(Collections.singleton(start));
				persistUnlockedAreas();
			}
			else
			{
				configManager.setConfiguration(STATE_GROUP, KEY_UNLOCKED_AREAS, "");
				areaGraphService.setUnlockedAreaIds(Collections.emptySet());
			}
		}
		List<String> areaIds = areaGraphService.getAreas().stream()
			.map(com.gridscape.data.Area::getId)
			.collect(Collectors.toList());
		taskGridServiceProvider.get().clearAllTaskProgress(areaIds);
		worldUnlockService.clearUnlocked();
		worldUnlockService.incrementGridSeed();
		globalTaskListService.clearGlobalTaskProgress();
		configManager.setConfiguration(STATE_GROUP, "pointsEarnedPerArea", "");
		configManager.setConfiguration(STATE_GROUP, "completedAreas", "");
		areaCompletionService.loadFromConfig();
		taskGridServiceProvider.get().incrementGridResetCounter();
		log.info("GridScape progress reset.");

		// Update overlays and UI to match reset: close floating panels then clear saved bounds (order avoids re-saving after unset).
		SwingUtilities.invokeLater(() -> {
			disposeTrackedDialog(worldUnlockDialogRef);
			worldUnlockDialogRef = null;
			disposeTrackedDialog(globalTasksDialogRef);
			globalTasksDialogRef = null;
			disposeTrackedDialog(goalsDialogRef);
			goalsDialogRef = null;
			gridScapeMapOverlay.closeProgressPopupsOnEdt();
			PanelBoundsStore.clearPanelBounds(configManager);
		});
		clientThread.invokeLater(() -> {
			if (client.getCanvas() != null)
			{
				client.getCanvas().repaint();
			}
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == net.runelite.api.GameState.LOGGED_IN)
			clientThread.invokeLater(this::tryOpenSetupForFirstTime);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		updateMapMouseListener();
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		// Area config: Shift+right-click on tile to add/move polygon corners when editing an area
		if (areaEditState.isEditingArea())
		{
			MenuAction action = event.getMenuEntry().getType();
			if (action == MenuAction.WALK || action == MenuAction.SET_HEADING)
			{
				int worldViewId = event.getMenuEntry().getWorldViewId();
				WorldView wv = client.getWorldView(worldViewId);
				if (wv == null) wv = client.getTopLevelWorldView();
				if (wv != null)
				{
					Tile tile = wv.getSelectedSceneTile();
					if (tile != null)
					{
						WorldPoint wp = tileToWorldPoint(client, tile, wv);
						if (wp != null && client.isKeyPressed(KeyCode.KC_SHIFT))
						{
							if (areaEditState.getMoveCornerIndex() >= 0)
							{
								client.createMenuEntry(-1)
									.setOption(SET_CORNER_OPTION)
									.setTarget(ADD_CORNER_TARGET)
									.setType(MenuAction.RUNELITE)
									.onClick(e -> setCornerAtSelectedTile());
								client.createMenuEntry(-1)
									.setOption(CANCEL_MOVE_OPTION)
									.setTarget(ADD_CORNER_TARGET)
									.setType(MenuAction.RUNELITE)
									.onClick(e -> { areaEditState.setMoveCornerIndex(-1); notifyCornersUpdated(); });
							}
							else
							{
								int idx = findCornerAt(wp.getX(), wp.getY(), wp.getPlane());
								if (idx >= 0)
								{
									final int cornerIdx = idx;
									client.createMenuEntry(-1)
										.setOption(MOVE_CORNER_OPTION)
										.setTarget(ADD_CORNER_TARGET)
										.setType(MenuAction.RUNELITE)
										.onClick(e -> { areaEditState.setMoveCornerIndex(cornerIdx); notifyCornersUpdated(); });
								}
								else
								{
									client.createMenuEntry(-1)
										.setOption(ADD_CORNER_OPTION)
										.setTarget(ADD_CORNER_TARGET)
										.setType(MenuAction.RUNELITE)
										.onClick(e -> addCornerAtSelectedTile());
								}
							}
							return;
						}
					}
				}
			}
		}

		boolean addViewAreaTasks = false;
		String option = event.getOption();
		// World map window is open and user right-clicked on it (Close entry) — add "View area tasks"
		Widget mapContainer = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (mapContainer != null && !mapContainer.isHidden())
		{
			Widget entryWidget = event.getMenuEntry().getWidget();
			if (entryWidget != null && isWidgetInMapHierarchy(entryWidget, mapContainer) && "Close".equals(option))
			{
				addViewAreaTasks = true;
			}
		}
		if (addViewAreaTasks)
		{
			client.createMenuEntry(-1)
				.setOption("View area tasks")
				.setTarget("World Map")
				.setType(MenuAction.RUNELITE)
				.onClick(e -> openTaskPopupForCurrentArea());
		}

	}

	private boolean isWidgetInMapHierarchy(Widget w, Widget mapContainer)
	{
		while (w != null)
		{
			if (w == mapContainer) return true;
			w = w.getParent();
		}
		return false;
	}

	/** Opens the task grid popup for the area the player is currently in. Call from UI or client thread. */
	public void openTasksForCurrentArea()
	{
		clientThread.invoke(() -> {
			if (client.getLocalPlayer() == null) return;
			net.runelite.api.coords.WorldPoint playerLoc = client.getLocalPlayer().getWorldLocation();
			com.gridscape.data.Area area = areaGraphService.getAreaAt(playerLoc);
			if (area == null) return;
			if (!areaGraphService.getUnlockedAreaIds().contains(area.getId())) return;
			gridScapeMapOverlay.openTaskGridForArea(area);
		});
	}

	/** Opens the World Unlock grid panel (World Unlock mode only). */
	public void openWorldUnlockGrid()
	{
		SwingUtilities.invokeLater(() -> {
			if (worldUnlockDialogRef != null && worldUnlockDialogRef.isDisplayable())
			{
				worldUnlockDialogRef.toFront();
				return;
			}
			java.awt.Frame owner = null;
			java.awt.Window w = SwingUtilities.windowForComponent(client.getCanvas());
			if (w instanceof java.awt.Frame) owner = (java.awt.Frame) w;
			JDialog dialog = new JDialog(owner, "World Unlock", false);
			dialog.setUndecorated(true);
			com.gridscape.worldunlock.WorldUnlockGridPanel panel = new com.gridscape.worldunlock.WorldUnlockGridPanel(
				worldUnlockService, pointsService,
				dialog::dispose,
				this::openGlobalTaskList,
				this::openSetupDialog,
				this::addUnlockedAreaId,
				client, clientThread, audioPlayer, dialog);
			dialog.setContentPane(panel);
			dialog.pack();
			dialog.setMinimumSize(com.gridscape.worldunlock.WorldUnlockUiDimensions.PANEL_PREFERRED);
			PanelBoundsStore.applyBounds(dialog, configManager,
				PanelBoundsStore.KEY_WORLD_UNLOCK, client.getCanvas());
			java.awt.Rectangle wub = dialog.getBounds();
			java.awt.Dimension wpref = com.gridscape.worldunlock.WorldUnlockUiDimensions.PANEL_PREFERRED;
			// Keep saved position only; size is always the shared panel design (not affected by other windows).
			dialog.setBounds(wub.x, wub.y, wpref.width, wpref.height);
			PanelBoundsStore.installPersistence(dialog, configManager,
				PanelBoundsStore.KEY_WORLD_UNLOCK);
			dialog.addWindowListener(new WindowAdapter()
			{
				@Override
				public void windowClosed(WindowEvent e)
				{
					if (worldUnlockDialogRef == dialog)
					{
						worldUnlockDialogRef = null;
					}
				}
			});
			worldUnlockDialogRef = dialog;
			registerEscapeToClose(dialog);
			dialog.setVisible(true);
		});
	}

	/** Opens the Goal tracking panel (from World Unlock grid Goals button). */
	public void openGoalTrackingPanel()
	{
		SwingUtilities.invokeLater(() -> {
			if (goalsDialogRef != null && goalsDialogRef.isDisplayable())
			{
				goalsDialogRef.toFront();
				return;
			}
			java.awt.Frame owner = null;
			java.awt.Window w = SwingUtilities.windowForComponent(client.getCanvas());
			if (w instanceof java.awt.Frame) owner = (java.awt.Frame) w;
			JDialog dialog = new JDialog(owner, "Goals", false);
			com.gridscape.worldunlock.GoalTrackingPanel panel = new com.gridscape.worldunlock.GoalTrackingPanel(
				goalTrackingService, worldUnlockService, dialog::dispose, client, audioPlayer);
			dialog.setContentPane(panel);
			dialog.pack();
			dialog.setSize(380, 300);
			com.gridscape.util.PanelBoundsStore.applyBounds(dialog, configManager,
				com.gridscape.util.PanelBoundsStore.KEY_GOALS, client.getCanvas());
			com.gridscape.util.PanelBoundsStore.installPersistence(dialog, configManager,
				com.gridscape.util.PanelBoundsStore.KEY_GOALS);
			dialog.addWindowListener(new WindowAdapter()
			{
				@Override
				public void windowClosed(WindowEvent e)
				{
					if (goalsDialogRef == dialog)
					{
						goalsDialogRef = null;
					}
				}
			});
			goalsDialogRef = dialog;
			registerEscapeToClose(dialog);
			dialog.setVisible(true);
		});
	}

	/** Opens the Global task list panel (World Unlock mode only). */
	public void openGlobalTaskList()
	{
		SwingUtilities.invokeLater(() -> {
			if (globalTasksDialogRef != null && globalTasksDialogRef.isDisplayable())
			{
				globalTasksDialogRef.toFront();
				java.awt.Component c = globalTasksDialogRef.getContentPane().getComponent(0);
				if (c instanceof com.gridscape.worldunlock.GlobalTaskListPanel)
					((com.gridscape.worldunlock.GlobalTaskListPanel) c).syncTaskHubVisibilityAndPosition();
				return;
			}
			java.awt.Frame owner = null;
			java.awt.Window w = SwingUtilities.windowForComponent(client.getCanvas());
			if (w instanceof java.awt.Frame) owner = (java.awt.Frame) w;
			JDialog dialog = new JDialog(owner, "Global tasks", false);
			dialog.setUndecorated(true);
			com.gridscape.worldunlock.GlobalTaskListPanel panel = new com.gridscape.worldunlock.GlobalTaskListPanel(
				globalTaskListService, pointsService, dialog::dispose, this::openWorldUnlockGrid, this::openSetupDialog, client, audioPlayer, clientThread, dialog);
			dialog.setContentPane(panel);
			dialog.pack();
			dialog.setMinimumSize(com.gridscape.worldunlock.WorldUnlockUiDimensions.PANEL_PREFERRED);
			PanelBoundsStore.applyBounds(dialog, configManager,
				PanelBoundsStore.KEY_GLOBAL_TASKS, client.getCanvas());
			java.awt.Rectangle gb = dialog.getBounds();
			java.awt.Dimension pref = com.gridscape.worldunlock.WorldUnlockUiDimensions.PANEL_PREFERRED;
			// Same fixed size as World Unlock. Do not use task hub width here — hub is a separate dialog.
			// Stale wide saves (e.g. old combined layout) are replaced so the grid panel matches World Unlock.
			dialog.setBounds(gb.x, gb.y, pref.width, pref.height);
			panel.attachTaskHub();
			PanelBoundsStore.installPersistence(dialog, configManager,
				PanelBoundsStore.KEY_GLOBAL_TASKS);
			dialog.addWindowListener(new WindowAdapter()
			{
				@Override
				public void windowClosed(WindowEvent e)
				{
					if (globalTasksDialogRef == dialog)
					{
						globalTasksDialogRef = null;
					}
				}
			});
			globalTasksDialogRef = dialog;
			registerEscapeToClose(dialog);
			dialog.setVisible(true);
			panel.syncTaskHubVisibilityAndPosition();
		});
	}

	private static void disposeTrackedDialog(JDialog d)
	{
		if (d != null && d.isDisplayable())
		{
			d.dispose();
		}
	}

	private void openTaskPopupForCurrentArea()
	{
		if (client.getLocalPlayer() == null) return;
		net.runelite.api.coords.WorldPoint playerLoc = client.getLocalPlayer().getWorldLocation();
		com.gridscape.data.Area area = areaGraphService.getAreaAt(playerLoc);
		if (area == null) return;
		if (!areaGraphService.getUnlockedAreaIds().contains(area.getId())) return;
		gridScapeMapOverlay.openTaskGridForArea(area);
	}

	private void updateMapMouseListener()
	{
		Widget mapContainer = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		boolean mapOpen = mapContainer != null && !mapContainer.isHidden();
		if (mapOpen && !mapMouseListenerRegistered)
		{
			mouseManager.registerMouseListener(gridScapeMapOverlay);
			mapMouseListenerRegistered = true;
		}
		else if (!mapOpen && mapMouseListenerRegistered)
		{
			mouseManager.unregisterMouseListener(gridScapeMapOverlay);
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

	/** Adds an area to the unlocked set and persists. Used after unlocking a World Unlock tile for an area so the map overlay stays in sync. */
	public void addUnlockedAreaId(String areaId)
	{
		boolean alreadyUnlocked = areaGraphService.getUnlockedAreaIds().contains(areaId);
		areaGraphService.addUnlocked(areaId);
		persistUnlockedAreas();
		if (!alreadyUnlocked && config.unlockMode() == GridScapeConfig.UnlockMode.WORLD_UNLOCK)
		{
			globalTaskListService.resetRepeatableSkillTaskProgressAfterAreaUnlock();
		}
	}

	// --- Area config editing API (used by GridScapeConfigPanel, AreaEditOverlay, GridScapeMapOverlay) ---

	public void startEditing(String areaId, List<int[]> initialCorners)
	{
		areaEditState.startEditing(areaId, initialCorners, areaGraphService.getArea(areaId));
		notifyCornersUpdated();
	}

	/** Start editing an area with multiple polygons (e.g. when loading existing area). */
	public void startEditingWithPolygons(String areaId, List<List<int[]>> polygons)
	{
		areaEditState.startEditingWithPolygons(areaId, polygons, areaGraphService.getArea(areaId));
		notifyCornersUpdated();
	}

	public void stopAreaEditing()
	{
		areaEditState.stopEditing();
		cornerUpdateCallback = null;
		neighborUpdateCallback = null;
	}

	/** Alias for config panel (same API as former config plugin). */
	public void stopEditing()
	{
		stopAreaEditing();
	}

	public void setCornerUpdateCallback(Consumer<List<int[]>> callback)
	{
		this.cornerUpdateCallback = callback;
	}

	public List<int[]> getEditingCorners()
	{
		return areaEditState.getEditingCorners();
	}

	/** Completed polygons (each with >= 3 corners). Current polygon is from getEditingCorners(). */
	public List<List<int[]>> getEditingPolygons()
	{
		return areaEditState.getEditingPolygons();
	}

	/** All polygons for save: editingPolygons + current polygon if it has >= 3 corners. */
	public List<List<int[]>> getAllEditingPolygons()
	{
		return areaEditState.getAllEditingPolygons();
	}

	/** Start a new polygon (commits current if >= 3 corners). Use in Add New Area or Edit Area on map. */
	public void startNewPolygon()
	{
		areaEditState.startNewPolygon();
		notifyCornersUpdated();
		client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Started new polygon.", null);
	}

	/**
	 * Remove the polygon at the given index (0-based over all polygons: completed first, then current if it has 3+ corners).
	 * Used when converting a polygon to a hole (remove it from the polygon list so it can be stored as a hole).
	 * @return the removed polygon, or null if index invalid or nothing to remove
	 */
	public List<int[]> removeEditingPolygonAt(int index)
	{
		List<int[]> removed = areaEditState.removeEditingPolygonAt(index);
		if (removed != null) notifyCornersUpdated();
		return removed;
	}

	/** Remove corner at index (for map right-click menu). */
	public void removeCorner(int index)
	{
		areaEditState.removeCorner(index);
		notifyCornersUpdated();
	}

	/** Set corner position (for map move-corner). */
	public void setCornerPosition(int index, net.runelite.api.coords.WorldPoint wp)
	{
		areaEditState.setCornerPosition(index, wp);
		notifyCornersUpdated();
	}

	public boolean isEditingArea()
	{
		return areaEditState.isEditingArea();
	}

	public boolean isAddNewAreaMode()
	{
		return areaEditState.isAddNewAreaMode();
	}

	public void addCornerFromWorldPoint(WorldPoint wp)
	{
		areaEditState.addCornerFromWorldPoint(wp);
		notifyCornersUpdated();
		client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Added corner: " + wp.getX() + ", " + wp.getY(), null);
	}

	public String getEditingAreaId()
	{
		return areaEditState.getEditingAreaId();
	}

	public int getMoveCornerIndex()
	{
		return areaEditState.getMoveCornerIndex();
	}

	/** Used by area-edit menu (Shift+right-click corner -> Move). */
	public void setMoveCornerIndex(int index)
	{
		areaEditState.setMoveCornerIndex(index);
		notifyCornersUpdated();
	}

	/** Holes for the area being edited (from area load or "Fill using others' corners"). */
	public List<List<int[]>> getEditingHoles()
	{
		return areaEditState.getEditingHoles();
	}

	/** Set holes (e.g. after "Fill using others' corners"). */
	public void setEditingHoles(List<List<int[]>> holes)
	{
		areaEditState.setEditingHoles(holes);
	}

	/** Neighbors for the area being edited (from load or "Add neighbors" on map). */
	public List<String> getEditingNeighbors()
	{
		return areaEditState.getEditingNeighbors();
	}

	public void setEditingNeighbors(List<String> neighbors)
	{
		areaEditState.setEditingNeighbors(neighbors);
		if (neighborUpdateCallback != null)
		{
			List<String> copy = new ArrayList<>(areaEditState.getEditingNeighbors());
			SwingUtilities.invokeLater(() -> neighborUpdateCallback.accept(copy));
		}
	}

	public void setNeighborUpdateCallback(Consumer<List<String>> callback)
	{
		this.neighborUpdateCallback = callback;
	}

	/** Replace the current polygon being edited (e.g. after paint-bucket fill). */
	public void setEditingCorners(List<int[]> corners)
	{
		areaEditState.setEditingCorners(corners);
		notifyCornersUpdated();
	}

	private void notifyCornersUpdated()
	{
		if (cornerUpdateCallback != null)
		{
			List<int[]> copy = new ArrayList<>(areaEditState.getEditingCorners());
			SwingUtilities.invokeLater(() -> cornerUpdateCallback.accept(copy));
		}
	}

	private void addCornerAtSelectedTile()
	{
		if (!areaEditState.isEditingArea()) return;
		WorldPoint wp = getSelectedWorldPoint();
		if (wp == null) return;
		areaEditState.addCornerFromWorldPoint(wp);
		notifyCornersUpdated();
		client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Added corner: " + wp.getX() + ", " + wp.getY(), null);
	}

	private void setCornerAtSelectedTile()
	{
		if (!areaEditState.isEditingArea() || areaEditState.getMoveCornerIndex() < 0) return;
		WorldPoint wp = getSelectedWorldPoint();
		if (wp == null) return;
		int moveCornerIndex = areaEditState.getMoveCornerIndex();
		if (moveCornerIndex < areaEditState.getEditingCorners().size())
		{
			areaEditState.setCornerPosition(moveCornerIndex, wp);
			notifyCornersUpdated();
			client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Moved corner #" + moveCornerIndex + " to " + wp.getX() + ", " + wp.getY(), null);
		}
		areaEditState.setMoveCornerIndex(-1);
	}

	private int findCornerAt(int x, int y, int plane)
	{
		return areaEditState.findCornerAt(x, y, plane);
	}

	private WorldPoint getSelectedWorldPoint()
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null) return null;
		Tile tile = wv.getSelectedSceneTile();
		if (tile == null) return null;
		return tileToWorldPoint(client, tile, wv);
	}

	private static WorldPoint tileToWorldPoint(Client client, Tile tile, WorldView wv)
	{
		if (tile == null || wv == null) return null;
		var local = tile.getLocalLocation();
		if (local == null) return null;
		if (client.isInInstancedRegion())
			return WorldPoint.fromLocalInstance(client, local);
		return WorldPoint.fromLocal(wv, local.getX(), local.getY(), wv.getPlane());
	}
}
