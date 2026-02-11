package com.leaguescape.lock;

import com.leaguescape.area.AreaGraphService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Tile;
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
		int worldViewId = event.getMenuEntry().getWorldViewId();
		var wv = client.getWorldView(worldViewId);
		if (wv == null)
		{
			wv = client.getTopLevelWorldView();
		}
		if (wv == null) return null;

		Tile selectedTile = wv.getSelectedSceneTile();
		if (selectedTile == null) return null;

		LocalPoint local = selectedTile.getLocalLocation();
		if (local == null) return null;

		if (client.isInInstancedRegion())
		{
			return WorldPoint.fromLocalInstance(client, local);
		}
		return WorldPoint.fromLocal(client, local);
	}
}
