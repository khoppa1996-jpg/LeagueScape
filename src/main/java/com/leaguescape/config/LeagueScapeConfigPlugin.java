package com.leaguescape.config;

import com.leaguescape.area.AreaGraphService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Configuration plugin for LeagueScape. Allows adding, editing, and removing areas.
 * Shift+Right-click on a tile to add a polygon corner when editing an area.
 */
@Slf4j
@PluginDescriptor(
	name = "LeagueScape Config",
	description = "Configure LeagueScape areas: add, edit, remove. Shift+Right-click to add corners; Shift+Right-click corner for Move, then Set new corner.",
	tags = {"leaguescape", "areas", "config"}
)
public class LeagueScapeConfigPlugin extends Plugin
{
	private static final String ADD_CORNER_OPTION = "Add polygon corner";
	private static final String ADD_CORNER_TARGET = "Tile";
	private static final String MOVE_CORNER_OPTION = "Move";
	private static final String SET_CORNER_OPTION = "Set new corner";
	private static final String CANCEL_MOVE_OPTION = "Cancel move";

	@Inject
	private Client client;

	@Inject
	private AreaGraphService areaGraphService;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private AreaEditOverlay areaEditOverlay;

	private LeagueScapeConfigPanel configPanel;
	private NavigationButton navButton;

	/** Area being edited (null = not editing). */
	private String editingAreaId = null;

	/** Corners for the area being edited. */
	private final List<int[]> editingCorners = new ArrayList<>();

	/** Index of corner being moved (-1 = not in move mode). */
	private int moveCornerIndex = -1;

	/** Called when corners change (from plugin thread). */
	private Consumer<List<int[]>> cornerUpdateCallback;

	@Override
	protected void startUp() throws Exception
	{
		overlayManager.add(areaEditOverlay);
		configPanel = new LeagueScapeConfigPanel(this, areaGraphService);
		navButton = NavigationButton.builder()
			.tooltip("LeagueScape Config")
			.icon(configPanel.getIcon())
			.priority(71)
			.panel(configPanel)
			.build();
		clientToolbar.addNavigation(navButton);
	}

	@Override
	protected void shutDown() throws Exception
	{
		stopEditing();
		overlayManager.remove(areaEditOverlay);
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}
	}

	/** Start editing an area. For new area, pass null and use a temporary id. */
	public void startEditing(String areaId, List<int[]> initialCorners)
	{
		editingAreaId = areaId;
		editingCorners.clear();
		if (initialCorners != null)
		{
			editingCorners.addAll(initialCorners);
		}
		notifyCornersUpdated();
	}

	/** Stop editing. */
	public void stopEditing()
	{
		editingAreaId = null;
		editingCorners.clear();
		moveCornerIndex = -1;
		cornerUpdateCallback = null;
	}

	/** Register callback for when corners change. Called from panel. */
	public void setCornerUpdateCallback(Consumer<List<int[]>> callback)
	{
		this.cornerUpdateCallback = callback;
	}

	/** Get current corners for the area being edited. */
	public List<int[]> getEditingCorners()
	{
		return Collections.unmodifiableList(new ArrayList<>(editingCorners));
	}

	/** Check if we're in editing mode. */
	public boolean isEditing()
	{
		return editingAreaId != null;
	}

	public String getEditingAreaId()
	{
		return editingAreaId;
	}

	/** Index of corner being moved (-1 if not in move mode). */
	public int getMoveCornerIndex()
	{
		return moveCornerIndex;
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (editingAreaId == null) return;

		MenuAction action = event.getMenuEntry().getType();
		if (action != MenuAction.WALK && action != MenuAction.SET_HEADING) return;

		int worldViewId = event.getMenuEntry().getWorldViewId();
		WorldView wv = client.getWorldView(worldViewId);
		if (wv == null) wv = client.getTopLevelWorldView();
		if (wv == null) return;

		Tile tile = wv.getSelectedSceneTile();
		if (tile == null) return;

		WorldPoint wp = tileToWorldPoint(tile, wv);
		if (wp == null) return;

		boolean shiftPressed = client.isKeyPressed(KeyCode.KC_SHIFT);
		if (!shiftPressed) return;

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
	}

	private void addCornerAtSelectedTile()
	{
		if (editingAreaId == null) return;

		WorldPoint wp = getSelectedWorldPoint();
		if (wp == null) return;

		int[] point = new int[]{wp.getX(), wp.getY(), wp.getPlane()};
		editingCorners.add(point);
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
			editingCorners.set(moveCornerIndex, new int[]{wp.getX(), wp.getY(), wp.getPlane()});
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
			{
				return i;
			}
		}
		return -1;
	}

	private WorldPoint getSelectedWorldPoint()
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null) return null;

		Tile tile = wv.getSelectedSceneTile();
		if (tile == null) return null;

		return tileToWorldPoint(tile, wv);
	}

	private WorldPoint tileToWorldPoint(Tile tile, WorldView wv)
	{
		if (tile == null || wv == null) return null;
		var local = tile.getLocalLocation();
		if (local == null) return null;
		if (client.isInInstancedRegion())
		{
			return WorldPoint.fromLocalInstance(client, local);
		}
		return WorldPoint.fromLocal(wv, local.getX(), local.getY(), wv.getPlane());
	}

	private void notifyCornersUpdated()
	{
		if (cornerUpdateCallback != null)
		{
			List<int[]> copy = new ArrayList<>(editingCorners);
			SwingUtilities.invokeLater(() -> cornerUpdateCallback.accept(copy));
		}
	}
}
