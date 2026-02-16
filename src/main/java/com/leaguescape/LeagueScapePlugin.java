package com.leaguescape;

import com.google.inject.Provides;
import com.google.inject.Provider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
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

@PluginDescriptor(
	name = "LeagueScape",
	enabledByDefault = true
)
public class LeagueScapePlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(LeagueScapePlugin.class);
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
	private com.leaguescape.points.AreaCompletionService areaCompletionService;

	@Inject
	private com.leaguescape.lock.LockEnforcer lockEnforcer;

	@Inject
	private com.leaguescape.overlay.LockedRegionOverlay lockedRegionOverlay;

	@Inject
	private com.leaguescape.overlay.TaskCompletionPopupOverlay taskCompletionPopupOverlay;

	@Inject
	private com.leaguescape.task.TaskCompletionListener taskCompletionListener;

	@Inject
	private com.leaguescape.overlay.LeagueScapeMapOverlay leagueScapeMapOverlay;

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

	private NavigationButton navButton;
	private NavigationButton configNavButton;
	private com.leaguescape.config.AreaEditOverlay areaEditOverlay;
	private boolean mapMouseListenerRegistered;

	// --- Area config editing (merged from LeagueScape Config plugin) ---
	private static final String ADD_CORNER_OPTION = "Add polygon corner";
	private static final String ADD_CORNER_TARGET = "Tile";
	private static final String MOVE_CORNER_OPTION = "Move";
	private static final String SET_CORNER_OPTION = "Set new corner";
	private static final String CANCEL_MOVE_OPTION = "Cancel move";
	/** Area being edited (null = not editing). */
	private String editingAreaId = null;
	/** Completed polygons (each has >= 3 corners). Current polygon is editingCorners. */
	private final List<List<int[]>> editingPolygons = new ArrayList<>();
	/** Corners for the current polygon being edited. */
	private final List<int[]> editingCorners = new ArrayList<>();
	/** Index of corner being moved (-1 = not in move mode). */
	private int moveCornerIndex = -1;
	/** Holes for the area being edited (subtracted from polygons). Set when loading area or by "Fill using others' corners". */
	private List<List<int[]>> editingHoles = null;
	/** Neighbors for the area being edited. Set when loading area; updated from config panel or map "Add neighbors" dialog. */
	private List<String> editingNeighbors = null;
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
		overlayManager.add(lockedRegionOverlay);
		overlayManager.add(taskCompletionPopupOverlay);
		overlayManager.add(leagueScapeMapOverlay);
		areaEditOverlay = areaEditOverlayProvider.get();
		overlayManager.add(areaEditOverlay);
		eventBus.register(taskCompletionListener);
		eventBus.register(this);
		// updateMapMouseListener() uses client (getWidget, isHidden) and must run on client thread; onGameTick will call it
		LeagueScapePanel panel = new LeagueScapePanel(this, config, configManager, areaGraphService, pointsService, areaCompletionService, audioPlayer);
		navButton = NavigationButton.builder()
			.tooltip("LeagueScape")
			.icon(panel.getIcon())
			.priority(70)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		com.leaguescape.config.LeagueScapeConfigPanel configPanel = new com.leaguescape.config.LeagueScapeConfigPanel(this, areaGraphService, taskGridServiceProvider.get(), configManager, config);
		configNavButton = NavigationButton.builder()
			.tooltip("LeagueScape Area Config")
			.icon(configPanel.getIcon())
			.priority(71)
			.panel(configPanel)
			.build();
		clientToolbar.addNavigation(configNavButton);
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("LeagueScape stopped!");
		stopAreaEditing();
		eventBus.unregister(taskCompletionListener);
		eventBus.unregister(this);
		if (mapMouseListenerRegistered)
		{
			mouseManager.unregisterMouseListener(leagueScapeMapOverlay);
			mapMouseListenerRegistered = false;
		}
		overlayManager.remove(lockedRegionOverlay);
		overlayManager.remove(taskCompletionPopupOverlay);
		overlayManager.remove(leagueScapeMapOverlay);
		if (areaEditOverlay != null)
		{
			overlayManager.remove(areaEditOverlay);
			areaEditOverlay = null;
		}
		eventBus.unregister(lockEnforcer);
		if (configNavButton != null)
		{
			clientToolbar.removeNavigation(configNavButton);
			configNavButton = null;
		}
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
	com.leaguescape.task.TaskCompletionListener provideTaskCompletionListener(Client client, com.leaguescape.area.AreaGraphService areaGraphService,
		com.leaguescape.task.TaskGridService taskGridService, com.leaguescape.overlay.TaskCompletionPopupOverlay taskCompletionPopupOverlay,
		AudioPlayer audioPlayer, net.runelite.client.game.ItemManager itemManager)
	{
		return new com.leaguescape.task.TaskCompletionListener(client, areaGraphService, taskGridService, taskCompletionPopupOverlay, audioPlayer, itemManager);
	}

	@Provides
	@Singleton
	com.leaguescape.wiki.OsrsWikiApiService provideOsrsWikiApiService()
	{
		return new com.leaguescape.wiki.OsrsWikiApiService();
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
		com.leaguescape.points.AreaCompletionService areaCompletionService)
	{
		return new com.leaguescape.task.TaskGridService(configManager, config, pointsService, areaCompletionService);
	}

	@Provides
	com.leaguescape.overlay.LeagueScapeMapOverlay provideLeagueScapeMapOverlay(Client client, com.leaguescape.area.AreaGraphService areaGraphService,
		LeagueScapeConfig config, com.leaguescape.points.PointsService pointsService,
		com.leaguescape.points.AreaCompletionService areaCompletionService,
		com.leaguescape.task.TaskGridService taskGridService,
		com.leaguescape.wiki.OsrsWikiApiService osrsWikiApiService,
		AudioPlayer audioPlayer)
	{
		return new com.leaguescape.overlay.LeagueScapeMapOverlay(client, areaGraphService, config, pointsService, areaCompletionService, this, taskGridService, osrsWikiApiService, audioPlayer);
	}

	@Provides
	com.leaguescape.config.AreaEditOverlay provideAreaEditOverlay(Client client, com.leaguescape.area.AreaGraphService areaGraphService,
		Provider<LeagueScapePlugin> pluginProvider)
	{
		return new com.leaguescape.config.AreaEditOverlay(client, areaGraphService, pluginProvider);
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

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		// Area config: Shift+right-click on tile to add/move polygon corners when editing an area
		if (editingAreaId != null)
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
							if (moveCornerIndex >= 0)
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
									.onClick(e -> { moveCornerIndex = -1; notifyCornersUpdated(); });
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
										.onClick(e -> { moveCornerIndex = cornerIdx; notifyCornersUpdated(); });
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

	// --- Area config editing API (used by LeagueScapeConfigPanel, AreaEditOverlay, LeagueScapeMapOverlay) ---

	public void startEditing(String areaId, List<int[]> initialCorners)
	{
		editingAreaId = areaId;
		editingPolygons.clear();
		editingCorners.clear();
		com.leaguescape.data.Area a = areaGraphService.getArea(areaId);
		editingHoles = (a != null && a.getHoles() != null) ? new ArrayList<>(a.getHoles()) : new ArrayList<>();
		editingNeighbors = (a != null && a.getNeighbors() != null) ? new ArrayList<>(a.getNeighbors()) : new ArrayList<>();
		if (initialCorners != null && !initialCorners.isEmpty())
		{
			editingCorners.addAll(initialCorners);
		}
		notifyCornersUpdated();
	}

	/** Start editing an area with multiple polygons (e.g. when loading existing area). */
	public void startEditingWithPolygons(String areaId, List<List<int[]>> polygons)
	{
		editingAreaId = areaId;
		editingPolygons.clear();
		editingCorners.clear();
		com.leaguescape.data.Area a = areaGraphService.getArea(areaId);
		editingHoles = (a != null && a.getHoles() != null) ? new ArrayList<>(a.getHoles()) : new ArrayList<>();
		editingNeighbors = (a != null && a.getNeighbors() != null) ? new ArrayList<>(a.getNeighbors()) : new ArrayList<>();
		if (polygons != null && !polygons.isEmpty())
		{
			for (int i = 0; i < polygons.size() - 1; i++)
			{
				List<int[]> poly = polygons.get(i);
				if (poly != null && poly.size() >= 3)
					editingPolygons.add(new ArrayList<>(poly));
			}
			List<int[]> last = polygons.get(polygons.size() - 1);
			if (last != null)
				editingCorners.addAll(last);
		}
		notifyCornersUpdated();
	}

	public void stopAreaEditing()
	{
		editingAreaId = null;
		editingPolygons.clear();
		editingCorners.clear();
		editingHoles = null;
		editingNeighbors = null;
		moveCornerIndex = -1;
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
		return Collections.unmodifiableList(new ArrayList<>(editingCorners));
	}

	/** Completed polygons (each with >= 3 corners). Current polygon is from getEditingCorners(). */
	public List<List<int[]>> getEditingPolygons()
	{
		return Collections.unmodifiableList(new ArrayList<>(editingPolygons));
	}

	/** All polygons for save: editingPolygons + current polygon if it has >= 3 corners. */
	public List<List<int[]>> getAllEditingPolygons()
	{
		List<List<int[]>> all = new ArrayList<>(editingPolygons);
		if (editingCorners.size() >= 3)
			all.add(new ArrayList<>(editingCorners));
		return all;
	}

	/** Start a new polygon (commits current if >= 3 corners). Use in Add New Area or Edit Area on map. */
	public void startNewPolygon()
	{
		if (editingCorners.size() >= 3)
			editingPolygons.add(new ArrayList<>(editingCorners));
		editingCorners.clear();
		moveCornerIndex = -1;
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
		if (index < 0) return null;
		if (index < editingPolygons.size())
		{
			List<int[]> removed = new ArrayList<>(editingPolygons.get(index));
			editingPolygons.remove(index);
			notifyCornersUpdated();
			return removed;
		}
		if (index == editingPolygons.size() && editingCorners.size() >= 3)
		{
			List<int[]> removed = new ArrayList<>(editingCorners);
			editingCorners.clear();
			moveCornerIndex = -1;
			notifyCornersUpdated();
			return removed;
		}
		return null;
	}

	/** Remove corner at index (for map right-click menu). */
	public void removeCorner(int index)
	{
		if (index < 0 || index >= editingCorners.size()) return;
		editingCorners.remove(index);
		if (moveCornerIndex == index) moveCornerIndex = -1;
		else if (moveCornerIndex > index) moveCornerIndex--;
		notifyCornersUpdated();
	}

	/** Set corner position (for map move-corner). */
	public void setCornerPosition(int index, net.runelite.api.coords.WorldPoint wp)
	{
		if (wp == null || index < 0 || index >= editingCorners.size()) return;
		editingCorners.set(index, new int[]{ wp.getX(), wp.getY(), wp.getPlane() });
		notifyCornersUpdated();
	}

	public boolean isEditingArea()
	{
		return editingAreaId != null;
	}

	public boolean isAddNewAreaMode()
	{
		return editingAreaId != null && editingAreaId.startsWith("new_");
	}

	public void addCornerFromWorldPoint(WorldPoint wp)
	{
		if (editingAreaId == null || wp == null) return;
		editingCorners.add(new int[]{ wp.getX(), wp.getY(), wp.getPlane() });
		notifyCornersUpdated();
		client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Added corner: " + wp.getX() + ", " + wp.getY(), null);
	}

	public String getEditingAreaId()
	{
		return editingAreaId;
	}

	public int getMoveCornerIndex()
	{
		return moveCornerIndex;
	}

	/** Used by area-edit menu (Shift+right-click corner -> Move). */
	public void setMoveCornerIndex(int index)
	{
		this.moveCornerIndex = index;
		notifyCornersUpdated();
	}

	/** Holes for the area being edited (from area load or "Fill using others' corners"). */
	public List<List<int[]>> getEditingHoles()
	{
		return editingHoles == null ? null : Collections.unmodifiableList(new ArrayList<>(editingHoles));
	}

	/** Set holes (e.g. after "Fill using others' corners"). */
	public void setEditingHoles(List<List<int[]>> holes)
	{
		this.editingHoles = (holes != null) ? new ArrayList<>(holes) : new ArrayList<>();
	}

	/** Neighbors for the area being edited (from load or "Add neighbors" on map). */
	public List<String> getEditingNeighbors()
	{
		return editingNeighbors == null ? null : Collections.unmodifiableList(new ArrayList<>(editingNeighbors));
	}

	public void setEditingNeighbors(List<String> neighbors)
	{
		this.editingNeighbors = (neighbors != null) ? new ArrayList<>(neighbors) : new ArrayList<>();
		if (neighborUpdateCallback != null)
		{
			List<String> copy = new ArrayList<>(this.editingNeighbors);
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
		editingCorners.clear();
		if (corners != null)
			editingCorners.addAll(corners);
		moveCornerIndex = -1;
		notifyCornersUpdated();
	}

	private void notifyCornersUpdated()
	{
		if (cornerUpdateCallback != null)
		{
			List<int[]> copy = new ArrayList<>(editingCorners);
			SwingUtilities.invokeLater(() -> cornerUpdateCallback.accept(copy));
		}
	}

	private void addCornerAtSelectedTile()
	{
		if (editingAreaId == null) return;
		WorldPoint wp = getSelectedWorldPoint();
		if (wp == null) return;
		editingCorners.add(new int[]{ wp.getX(), wp.getY(), wp.getPlane() });
		notifyCornersUpdated();
		client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Added corner: " + wp.getX() + ", " + wp.getY(), null);
	}

	private void setCornerAtSelectedTile()
	{
		if (editingAreaId == null || moveCornerIndex < 0) return;
		WorldPoint wp = getSelectedWorldPoint();
		if (wp == null) return;
		if (moveCornerIndex < editingCorners.size())
		{
			editingCorners.set(moveCornerIndex, new int[]{ wp.getX(), wp.getY(), wp.getPlane() });
			notifyCornersUpdated();
			client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Moved corner #" + moveCornerIndex + " to " + wp.getX() + ", " + wp.getY(), null);
		}
		moveCornerIndex = -1;
	}

	private int findCornerAt(int x, int y, int plane)
	{
		for (int i = 0; i < editingCorners.size(); i++)
		{
			int[] c = editingCorners.get(i);
			if (c.length >= 3 && c[0] == x && c[1] == y && c[2] == plane)
				return i;
		}
		return -1;
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
