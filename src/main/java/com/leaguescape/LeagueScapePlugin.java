package com.leaguescape;

import com.google.inject.Provides;
import com.google.inject.Provider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import java.awt.event.KeyEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
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
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * LeagueScape plugin: area-based progression with unlockable regions, task grids per area, and
 * point economy. Loads areas from areas.json (and custom/removed from config), tracks unlocked
 * areas and points, enforces locking of tiles in locked areas, and provides the world-map overlay
 * (area details, task grid popups) and side panel (points, unlock buttons, tasks). Config editing
 * (areas, tasks) is in the config panel; area polygon editing uses right-click "Add polygon corner"
 * and "Set new corner" on the world map.
 */
@PluginDescriptor(
	name = "LeagueScape",
	enabledByDefault = true
)
public class LeagueScapePlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(LeagueScapePlugin.class);
	/** Config group for persisted state (unlocked areas, task progress, etc.). */
	private static final String STATE_GROUP = com.leaguescape.util.LeagueScapeConfigConstants.STATE_GROUP;

	/** Registers Escape key to close the given window (dispose). Call after creating a JDialog/JFrame. */
	public static void registerEscapeToClose(java.awt.Window window)
	{
		com.leaguescape.util.LeagueScapeSwingUtil.registerEscapeToClose(window);
	}
	private static final String KEY_UNLOCKED_AREAS = "unlockedAreas";
	/** Comma-separated list of usernames for which the Rules & Setup panel has been shown (first-time open). */
	private static final String KEY_SETUP_OPENED_ACCOUNTS = "setupOpenedAccounts";

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
	private com.leaguescape.points.AreaCompletionService areaCompletionService;

	@Inject
	private com.leaguescape.lock.LockEnforcer lockEnforcer;

	@Inject
	private com.leaguescape.overlay.LockedRegionOverlay lockedRegionOverlay;

	@Inject
	private com.leaguescape.overlay.TaskCompletionPopupOverlay taskCompletionPopupOverlay;

	@Inject
	private com.leaguescape.overlay.LeagueScapeMapOverlay leagueScapeMapOverlay;

	@Inject
	private com.leaguescape.overlay.LeagueScapeMinimapButtonOverlay leagueScapeMinimapButtonOverlay;

	@Inject
	private Provider<com.leaguescape.config.AreaEditOverlay> areaEditOverlayProvider;

	@Inject
	private Provider<com.leaguescape.task.TaskGridService> taskGridServiceProvider;

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
	private com.leaguescape.wiki.OsrsWikiApiService osrsWikiApiService;

	@Inject
	private com.leaguescape.wiki.WikiTaskGenerator wikiTaskGenerator;

	@Inject
	private com.leaguescape.worldunlock.WorldUnlockService worldUnlockService;

	@Inject
	private com.leaguescape.worldunlock.GlobalTaskListService globalTaskListService;

	@Inject
	private com.leaguescape.worldunlock.GoalTrackingService goalTrackingService;

	private NavigationButton navButton;
	private com.leaguescape.config.AreaEditOverlay areaEditOverlay;
	private boolean mapMouseListenerRegistered;

	// --- Area config editing (merged from LeagueScape Config plugin) ---
	private static final String ADD_CORNER_OPTION = "Add polygon corner";
	private static final String ADD_CORNER_TARGET = "Tile";
	private static final String MOVE_CORNER_OPTION = "Move";
	private static final String SET_CORNER_OPTION = "Set new corner";
	private static final String CANCEL_MOVE_OPTION = "Cancel move";
	private final com.leaguescape.config.AreaEditState areaEditState = new com.leaguescape.config.AreaEditState();
	/** Called when corners change (from plugin thread). */
	private Consumer<List<int[]>> cornerUpdateCallback;
	/** Called when neighbors change (e.g. from map "Add neighbors" dialog). */
	private Consumer<List<String>> neighborUpdateCallback;

	@Override
	protected void startUp() throws Exception
	{
		log.info("LeagueScape started!");
		eventBus.register(lockEnforcer);
		pointsService.loadFromConfig();
		areaCompletionService.loadFromConfig();
		// Apply configured starting points when no persisted state exists (first run)
		if (pointsService.getEarnedTotal() == 0 && pointsService.getSpentTotal() == 0)
		{
			pointsService.setStartingPoints(config.startingPoints());
		}
		loadUnlockedAreas();
		if (config.unlockMode() == LeagueScapeConfig.UnlockMode.WORLD_UNLOCK)
		{
			worldUnlockService.load();
		}
		overlayManager.add(lockedRegionOverlay);
		overlayManager.add(taskCompletionPopupOverlay);
		overlayManager.add(leagueScapeMapOverlay);
		overlayManager.add(leagueScapeMinimapButtonOverlay);
		mouseManager.registerMouseListener(leagueScapeMinimapButtonOverlay);
		areaEditOverlay = areaEditOverlayProvider.get();
		overlayManager.add(areaEditOverlay);
		eventBus.register(this);
		// updateMapMouseListener() uses client (getWidget, isHidden) and must run on client thread; onGameTick will call it
		LeagueScapePanel panel = new LeagueScapePanel(this, config, configManager, areaGraphService, pointsService, areaCompletionService, audioPlayer, client);
		navButton = NavigationButton.builder()
			.tooltip("LeagueScape")
			.icon(panel.getIcon())
			.priority(70)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		// Open Rules & Setup panel for this account if first time (by username)
		clientThread.invokeLater(this::tryOpenSetupForFirstTime);
	}

	/**
	 * If the current account (by username) has never been shown the Rules & Setup panel, open it centered on the game client and mark as shown.
	 */
	private void tryOpenSetupForFirstTime()
	{
		String username = client.getUsername();
		if (username == null || username.isEmpty())
			return;
		String raw = configManager.getConfiguration(STATE_GROUP, KEY_SETUP_OPENED_ACCOUNTS);
		java.util.Set<String> seen = com.leaguescape.util.ConfigParsing.parseCommaSeparatedSet(raw);
		if (seen.contains(username))
			return;
		seen.add(username);
		configManager.setConfiguration(STATE_GROUP, KEY_SETUP_OPENED_ACCOUNTS, com.leaguescape.util.ConfigParsing.joinComma(seen));
		openSetupDialog();
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("LeagueScape stopped!");
		stopAreaEditing();
		eventBus.unregister(this);
		if (mapMouseListenerRegistered)
		{
			mouseManager.unregisterMouseListener(leagueScapeMapOverlay);
			mapMouseListenerRegistered = false;
		}
		overlayManager.remove(lockedRegionOverlay);
		overlayManager.remove(taskCompletionPopupOverlay);
		overlayManager.remove(leagueScapeMapOverlay);
		overlayManager.remove(leagueScapeMinimapButtonOverlay);
		mouseManager.unregisterMouseListener(leagueScapeMinimapButtonOverlay);
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
	@Singleton
	com.leaguescape.points.AreaCompletionService provideAreaCompletionService(ConfigManager configManager,
		com.leaguescape.area.AreaGraphService areaGraphService, com.leaguescape.points.PointsService pointsService,
		LeagueScapeConfig config, javax.inject.Provider<com.leaguescape.task.TaskGridService> taskGridServiceProvider)
	{
		return new com.leaguescape.points.AreaCompletionService(configManager, areaGraphService, pointsService, config, taskGridServiceProvider);
	}

	@Provides
	com.leaguescape.lock.LockEnforcer provideLockEnforcer(Client client, LeagueScapeConfig config, com.leaguescape.area.AreaGraphService areaGraphService)
	{
		return new com.leaguescape.lock.LockEnforcer(client, config, areaGraphService);
	}

	@Provides
	com.leaguescape.overlay.LockedRegionOverlay provideLockedRegionOverlay(Client client, com.leaguescape.area.AreaGraphService areaGraphService, LeagueScapeConfig config)
	{
		return new com.leaguescape.overlay.LockedRegionOverlay(client, areaGraphService, config);
	}

	@Provides
	com.leaguescape.overlay.TaskCompletionPopupOverlay provideTaskCompletionPopupOverlay(Client client)
	{
		return new com.leaguescape.overlay.TaskCompletionPopupOverlay(client);
	}

	@Provides
	@Singleton
	com.leaguescape.wiki.OsrsWikiApiService provideOsrsWikiApiService()
	{
		return new com.leaguescape.wiki.OsrsWikiApiService();
	}

	@Provides
	@Singleton
	com.leaguescape.wiki.WikiTaskGenerator provideWikiTaskGenerator(com.leaguescape.wiki.OsrsWikiApiService osrsWikiApiService)
	{
		return new com.leaguescape.wiki.WikiTaskGenerator(osrsWikiApiService);
	}

	@Provides
	@Singleton
	com.leaguescape.wiki.OsrsItemService provideOsrsItemService()
	{
		return new com.leaguescape.wiki.OsrsItemService();
	}

	@Provides
	@Singleton
	com.leaguescape.task.TaskGridService provideTaskGridService(ConfigManager configManager, LeagueScapeConfig config,
		com.leaguescape.points.PointsService pointsService,
		com.leaguescape.points.AreaCompletionService areaCompletionService,
		com.leaguescape.area.AreaGraphService areaGraphService,
		Client client)
	{
		return new com.leaguescape.task.TaskGridService(configManager, config, pointsService, areaCompletionService, areaGraphService, client);
	}

	@Provides
	com.leaguescape.overlay.LeagueScapeMapOverlay provideLeagueScapeMapOverlay(Client client, com.leaguescape.area.AreaGraphService areaGraphService,
		LeagueScapeConfig config, com.leaguescape.points.PointsService pointsService,
		com.leaguescape.points.AreaCompletionService areaCompletionService,
		com.leaguescape.task.TaskGridService taskGridService,
		com.leaguescape.worldunlock.WorldUnlockService worldUnlockService,
		com.leaguescape.wiki.OsrsWikiApiService osrsWikiApiService,
		AudioPlayer audioPlayer, net.runelite.client.callback.ClientThread clientThread)
	{
		return new com.leaguescape.overlay.LeagueScapeMapOverlay(client, areaGraphService, config, pointsService, areaCompletionService, this, taskGridService, worldUnlockService, osrsWikiApiService, audioPlayer, clientThread);
	}

	@Provides
	com.leaguescape.config.AreaEditOverlay provideAreaEditOverlay(Client client, com.leaguescape.area.AreaGraphService areaGraphService,
		Provider<LeagueScapePlugin> pluginProvider)
	{
		return new com.leaguescape.config.AreaEditOverlay(client, areaGraphService, pluginProvider);
	}

	@Provides
	@Singleton
	com.leaguescape.worldunlock.WorldUnlockService provideWorldUnlockService(ConfigManager configManager,
		LeagueScapeConfig config,
		com.leaguescape.points.PointsService pointsService,
		com.leaguescape.task.TaskGridService taskGridService,
		com.leaguescape.area.AreaGraphService areaGraphService)
	{
		return new com.leaguescape.worldunlock.WorldUnlockService(configManager, config, pointsService, taskGridService, areaGraphService);
	}

	@Provides
	@Singleton
	com.leaguescape.worldunlock.GlobalTaskListService provideGlobalTaskListService(ConfigManager configManager,
		LeagueScapeConfig config,
		com.leaguescape.points.PointsService pointsService,
		com.leaguescape.worldunlock.WorldUnlockService worldUnlockService,
		com.leaguescape.task.TaskGridService taskGridService)
	{
		return new com.leaguescape.worldunlock.GlobalTaskListService(configManager, config, pointsService, worldUnlockService, taskGridService);
	}

	@Provides
	@Singleton
	com.leaguescape.worldunlock.GoalTrackingService provideGoalTrackingService()
	{
		return new com.leaguescape.worldunlock.GoalTrackingService();
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
			if (config.unlockMode() == LeagueScapeConfig.UnlockMode.WORLD_UNLOCK)
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
	 * Opens the LeagueScape Rules and Setup popup (moveable, resizable) with tabs: Rules, Game Mode,
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
			com.leaguescape.config.LeagueScapeSetupFrame frame = new com.leaguescape.config.LeagueScapeSetupFrame(
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
			registerEscapeToClose(frame);
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
		if (config.unlockMode() == LeagueScapeConfig.UnlockMode.WORLD_UNLOCK && worldUnlockService.getUnlockedIds().size() > 0)
			return true;
		return false;
	}

	/**
	 * Resets all LeagueScape progress: points to 0, all area unlocks cleared, all task completions
	 * cleared, area completion state (points-to-complete mode) cleared, and task grids reshuffled.
	 * Does not remove custom areas or custom tasks.
	 */
	public void resetProgress()
	{
		pointsService.setStartingPoints(0);
		// Clear unlocks. In World Unlock mode, starter stays locked until unlocked on the grid.
		if (config.unlockMode() == LeagueScapeConfig.UnlockMode.WORLD_UNLOCK)
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
			.map(com.leaguescape.data.Area::getId)
			.collect(Collectors.toList());
		taskGridServiceProvider.get().clearAllTaskProgress(areaIds);
		worldUnlockService.clearUnlocked();
		worldUnlockService.incrementGridSeed();
		globalTaskListService.clearGlobalTaskProgress();
		configManager.setConfiguration(STATE_GROUP, "pointsEarnedPerArea", "");
		configManager.setConfiguration(STATE_GROUP, "completedAreas", "");
		areaCompletionService.loadFromConfig();
		taskGridServiceProvider.get().incrementGridResetCounter();
		log.info("LeagueScape progress reset.");

		// Update overlays and UI to match reset: close progress popups and request repaint
		leagueScapeMapOverlay.closeProgressPopups();
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
			com.leaguescape.data.Area area = areaGraphService.getAreaAt(playerLoc);
			if (area == null) return;
			if (!areaGraphService.getUnlockedAreaIds().contains(area.getId())) return;
			leagueScapeMapOverlay.openTaskGridForArea(area);
		});
	}

	/** Opens the World Unlock grid panel (World Unlock mode only). */
	public void openWorldUnlockGrid()
	{
		javax.swing.SwingUtilities.invokeLater(() -> {
			java.awt.Frame owner = null;
			java.awt.Window w = javax.swing.SwingUtilities.windowForComponent(client.getCanvas());
			if (w instanceof java.awt.Frame) owner = (java.awt.Frame) w;
			javax.swing.JDialog dialog = new javax.swing.JDialog(owner, "World Unlock", false);
			dialog.setUndecorated(true);
			com.leaguescape.worldunlock.WorldUnlockGridPanel panel = new com.leaguescape.worldunlock.WorldUnlockGridPanel(
				worldUnlockService, pointsService,
				dialog::dispose,
				this::openGlobalTaskList,
				this::openSetupDialog,
				this::addUnlockedAreaId,
				client, audioPlayer, dialog);
			dialog.setContentPane(panel);
			dialog.pack();
			dialog.setLocationRelativeTo(client.getCanvas());
			registerEscapeToClose(dialog);
			dialog.setVisible(true);
		});
	}

	/** Opens the Goal tracking panel (from World Unlock grid Goals button). */
	public void openGoalTrackingPanel()
	{
		javax.swing.SwingUtilities.invokeLater(() -> {
			java.awt.Frame owner = null;
			java.awt.Window w = javax.swing.SwingUtilities.windowForComponent(client.getCanvas());
			if (w instanceof java.awt.Frame) owner = (java.awt.Frame) w;
			javax.swing.JDialog dialog = new javax.swing.JDialog(owner, "Goals", false);
			com.leaguescape.worldunlock.GoalTrackingPanel panel = new com.leaguescape.worldunlock.GoalTrackingPanel(
				goalTrackingService, worldUnlockService, dialog::dispose, client, audioPlayer);
			dialog.setContentPane(panel);
			dialog.pack();
			dialog.setSize(380, 300);
			dialog.setLocationRelativeTo(client.getCanvas());
			registerEscapeToClose(dialog);
			dialog.setVisible(true);
		});
	}

	/** Opens the Global task list panel (World Unlock mode only). */
	public void openGlobalTaskList()
	{
		javax.swing.SwingUtilities.invokeLater(() -> {
			java.awt.Frame owner = null;
			java.awt.Window w = javax.swing.SwingUtilities.windowForComponent(client.getCanvas());
			if (w instanceof java.awt.Frame) owner = (java.awt.Frame) w;
			javax.swing.JDialog dialog = new javax.swing.JDialog(owner, "Global tasks", false);
			dialog.setUndecorated(true);
			com.leaguescape.worldunlock.GlobalTaskListPanel panel = new com.leaguescape.worldunlock.GlobalTaskListPanel(
				globalTaskListService, pointsService, dialog::dispose, this::openSetupDialog, client, audioPlayer, clientThread, dialog);
			dialog.setContentPane(panel);
			dialog.pack();
			dialog.setLocationRelativeTo(client.getCanvas());
			registerEscapeToClose(dialog);
			dialog.setVisible(true);
		});
	}

	private void openTaskPopupForCurrentArea()
	{
		if (client.getLocalPlayer() == null) return;
		net.runelite.api.coords.WorldPoint playerLoc = client.getLocalPlayer().getWorldLocation();
		com.leaguescape.data.Area area = areaGraphService.getAreaAt(playerLoc);
		if (area == null) return;
		if (!areaGraphService.getUnlockedAreaIds().contains(area.getId())) return;
		leagueScapeMapOverlay.openTaskGridForArea(area);
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

	/** Adds an area to the unlocked set and persists. Used after unlocking a World Unlock tile for an area so the map overlay stays in sync. */
	public void addUnlockedAreaId(String areaId)
	{
		areaGraphService.addUnlocked(areaId);
		persistUnlockedAreas();
	}

	// --- Area config editing API (used by LeagueScapeConfigPanel, AreaEditOverlay, LeagueScapeMapOverlay) ---

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
