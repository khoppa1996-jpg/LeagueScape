package com.leaguescape.lock;

import com.leaguescape.area.AreaGraphService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Prevents interaction with locked areas: consume walk/click to locked tiles,
 * show breach banner when player is outside unlocked space.
 */
@Slf4j
public class LockEnforcer
{
	private final Client client;
	private final AreaGraphService areaGraphService;

	private boolean inLockedZone = false;

	@Inject
	public LockEnforcer(Client client, AreaGraphService areaGraphService)
	{
		this.client = client;
		this.areaGraphService = areaGraphService;
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (client.getLocalPlayer() == null) return;

		String option = Text.removeFormattingTags(event.getMenuOption());
		// Skip Cancel - it just closes the menu and has no spatial target
		if ("Cancel".equals(option)) return;

		// Resolve target tile: works for Walk here, object clicks, NPC clicks, ground items, etc.
		// The selected scene tile is set when the user right-clicks or left-clicks on the world
		WorldPoint target = getTargetWorldPoint(event);
		if (target == null) return; // No spatial target (e.g. widget-only action) - allow

		if (!areaGraphService.isWorldPointUnlocked(target))
		{
			event.consume();
			client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Locked area.", null);
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (client.getLocalPlayer() == null) return;
		LocalPoint local = client.getLocalPlayer().getLocalLocation();
		if (local == null) return;
		WorldPoint world;
		if (client.isInInstancedRegion())
		{
			world = WorldPoint.fromLocalInstance(client, local);
		}
		else
		{
			world = WorldPoint.fromLocal(client, local);
		}
		inLockedZone = world != null && !areaGraphService.isWorldPointUnlocked(world);
	}

	public boolean isInLockedZone()
	{
		return inLockedZone;
	}

	/**
	 * Resolve the target world point for a menu action.
	 * Uses the client's selected scene tile (the tile under the cursor when the user clicked).
	 * Works for Walk here, object interactions, NPC interactions, ground items, and targeted spells.
	 * Returns null if the target cannot be resolved - in that case we do NOT block the click.
	 */
	private WorldPoint getTargetWorldPoint(MenuOptionClicked event)
	{
		var entry = event.getMenuEntry();
		// Do not use selected scene tile when the click was on a widget/UI (e.g. inventory, spell).
		// The selected tile would be whatever is under the UI on the main view and can be locked,
		// which would wrongly block all interface interactions when they appear over locked areas.
		if (entry.getWidget() != null)
		{
			return null;
		}

		MenuAction action = entry.getType();
		// Only enforce for actions that actually target a world tile. Skip widget-only and runelite actions.
		if (!isWorldTargetingAction(action))
		{
			return null;
		}

		WorldView wv = client.getWorldView(entry.getWorldViewId());
		if (wv == null)
		{
			wv = client.getTopLevelWorldView();
		}
		if (wv == null) return null;

		Tile selectedTile = wv.getSelectedSceneTile();
		// For WALK/SET_HEADING (e.g. minimap click), the destination may be in a different world view (minimap).
		if (selectedTile == null && (action == MenuAction.WALK || action == MenuAction.SET_HEADING))
		{
			selectedTile = findSelectedTileInAnyWorldView();
		}
		if (selectedTile == null) return null;

		LocalPoint local = selectedTile.getLocalLocation();
		if (local == null) return null;

		if (client.isInInstancedRegion())
		{
			return WorldPoint.fromLocalInstance(client, local);
		}
		return WorldPoint.fromLocal(client, local);
	}

	/** True if this menu action targets a tile in the world (walk, object, NPC, ground item, etc.). */
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

	/** Find a selected scene tile in the top-level world view or any of its children (e.g. minimap). */
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
