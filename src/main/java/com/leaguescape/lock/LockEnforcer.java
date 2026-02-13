package com.leaguescape.lock;

import com.leaguescape.LeagueScapeConfig;
import com.leaguescape.area.AreaGraphService;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * When the locked region overlay is active, blocks any click on a tile where the overlay
 * is drawn. Uses the same logic as the overlay: the tile must be inside a locked area's
 * polygon (getTilesInLockedAreas). If the click is inside a locked polygon, intercept and
 * block; otherwise do nothing.
 */
@Slf4j
public class LockEnforcer
{
	private final Client client;
	private final LeagueScapeConfig config;
	private final AreaGraphService areaGraphService;

	/** Tile under the cursor, updated every tick so we have it when menu events fire. */
	private WorldPoint cursorTileWorldPoint = null;
	private boolean inLockedZone = false;

	@Inject
	public LockEnforcer(Client client, LeagueScapeConfig config, AreaGraphService areaGraphService)
	{
		this.client = client;
		this.config = config;
		this.areaGraphService = areaGraphService;
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (client.getLocalPlayer() == null)
		{
			cursorTileWorldPoint = null;
			return;
		}
		// Capture the tile under the cursor every tick so we have it when MenuOpened/MenuOptionClicked fire
		cursorTileWorldPoint = getSelectedTileWorldPoint();

		LocalPoint local = client.getLocalPlayer().getLocalLocation();
		if (local == null) return;
		WorldPoint world;
		if (client.isInInstancedRegion())
			world = WorldPoint.fromLocalInstance(client, local);
		else
			world = WorldPoint.fromLocal(client, local);
		// Same as overlay: player is in locked zone if their tile is in the locked-tiles set (polygon-based)
		inLockedZone = world != null && areaGraphService.getTilesInLockedAreas(world.getPlane()).contains(world);
	}

	/**
	 * If the overlay is active and the click was on a locked tile, remove all world-targeting
	 * menu entries so the user cannot choose any of them.
	 */
	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (!config.renderLockedOverlay()) return;
		if (client.getLocalPlayer() == null) return;

		WorldPoint clickedTile = getClickedTileWorldPoint();
		if (clickedTile == null) return;
		// Same logic as overlay: block only if this tile is in the locked polygon set (not includes-based)
		if (!areaGraphService.getTilesInLockedAreas(clickedTile.getPlane()).contains(clickedTile)) return;

		// Click was on a locked overlay tile: remove every entry that targets the world (walk, object, npc, etc.)
		MenuEntry[] entries = client.getMenuEntries();
		if (entries == null || entries.length == 0) return;

		List<MenuEntry> keep = new ArrayList<>(entries.length);
		for (MenuEntry entry : entries)
		{
			if (entry.getWidget() != null)
			{
				keep.add(entry);
				continue;
			}
			if (!isWorldTargetingAction(entry.getType()))
				keep.add(entry);
		}
		if (keep.size() < entries.length)
			client.setMenuEntries(keep.toArray(new MenuEntry[0]));
	}

	/**
	 * If the overlay is active and the click was on a locked tile, consume the event so
	 * the game never receives the action (no path, no interact).
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!config.renderLockedOverlay()) return;
		if (client.getLocalPlayer() == null) return;

		String option = Text.removeFormattingTags(event.getMenuOption());
		if ("Cancel".equals(option)) return;
		// Clicks on widgets (inventory, spellbook, etc.) are not blocked by the overlay
		if (event.getMenuEntry().getWidget() != null) return;
		if (!isWorldTargetingAction(event.getMenuEntry().getType())) return;

		WorldPoint clickedTile = getClickedTileWorldPoint();
		if (clickedTile == null) return;
		// Same logic as overlay: block only if this tile is inside a locked polygon
		if (!areaGraphService.getTilesInLockedAreas(clickedTile.getPlane()).contains(clickedTile)) return;

		// Click was on a locked overlay tile: block it
		event.consume();
		client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Locked area.", null);
	}

	public boolean isInLockedZone()
	{
		return inLockedZone;
	}

	/** The tile under the cursor (where the user clicked). Uses last tick's value so it's set when menu events run. */
	private WorldPoint getClickedTileWorldPoint()
	{
		// Prefer current selected tile in case it's still set
		WorldPoint current = getSelectedTileWorldPoint();
		if (current != null) return current;
		return cursorTileWorldPoint;
	}

	/** Current selected scene tile as world point (tile under cursor), from main view or minimap. */
	private WorldPoint getSelectedTileWorldPoint()
	{
		Tile tile = findSelectedTileInAnyWorldView();
		if (tile == null) return null;
		LocalPoint local = tile.getLocalLocation();
		if (local == null) return null;
		if (client.isInInstancedRegion())
			return WorldPoint.fromLocalInstance(client, local);
		return WorldPoint.fromLocal(client, local);
	}

	private static boolean isWorldTargetingAction(MenuAction action)
	{
		switch (action)
		{
			case WALK:
			case SET_HEADING:
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case NPC_FIRST_OPTION:
			case NPC_SECOND_OPTION:
			case NPC_THIRD_OPTION:
			case NPC_FOURTH_OPTION:
			case NPC_FIFTH_OPTION:
			case GROUND_ITEM_FIRST_OPTION:
			case GROUND_ITEM_SECOND_OPTION:
			case GROUND_ITEM_THIRD_OPTION:
			case GROUND_ITEM_FOURTH_OPTION:
			case GROUND_ITEM_FIFTH_OPTION:
			case PLAYER_FIRST_OPTION:
			case PLAYER_SECOND_OPTION:
			case PLAYER_THIRD_OPTION:
			case PLAYER_FOURTH_OPTION:
			case PLAYER_FIFTH_OPTION:
			case PLAYER_SIXTH_OPTION:
			case PLAYER_SEVENTH_OPTION:
			case PLAYER_EIGHTH_OPTION:
			case WIDGET_TARGET_ON_GAME_OBJECT:
			case WIDGET_TARGET_ON_GROUND_ITEM:
			case WIDGET_TARGET_ON_NPC:
			case WIDGET_TARGET_ON_PLAYER:
			case WORLD_ENTITY_FIRST_OPTION:
			case WORLD_ENTITY_SECOND_OPTION:
			case WORLD_ENTITY_THIRD_OPTION:
			case WORLD_ENTITY_FOURTH_OPTION:
			case WORLD_ENTITY_FIFTH_OPTION:
			case EXAMINE_OBJECT:
			case EXAMINE_NPC:
			case EXAMINE_ITEM_GROUND:
			case EXAMINE_WORLD_ENTITY:
				return true;
			default:
				return false;
		}
	}

	private Tile findSelectedTileInAnyWorldView()
	{
		WorldView top = client.getTopLevelWorldView();
		if (top == null) return null;
		Tile t = top.getSelectedSceneTile();
		if (t != null) return t;
		try
		{
			for (WorldView child : top.worldViews())
			{
				t = child.getSelectedSceneTile();
				if (t != null) return t;
			}
		}
		catch (Exception ignored) { }
		return null;
	}
}
