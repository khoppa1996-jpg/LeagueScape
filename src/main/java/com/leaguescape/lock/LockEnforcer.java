package com.leaguescape.lock;

import com.leaguescape.area.AreaGraphService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
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
		// "Walk here" - param0, param1 are canvas coords; we need world tile
		String option = Text.removeFormattingTags(event.getMenuOption());
		if ("Walk here".equals(option))
		{
			WorldPoint target = getWorldPointFromMenu(event.getParam0(), event.getParam1());
			if (target != null && !areaGraphService.isWorldPointUnlocked(target))
			{
				event.consume();
				client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Locked area.", null);
			}
		}
		// TODO: object/NPC interactions - resolve target tile from param0/param1/type
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

	/** Resolve menu click to world tile. Param0/1 for Walk here are typically x/y. */
	private WorldPoint getWorldPointFromMenu(int param0, int param1)
	{
		// For "Walk here", the client uses param0/param1 as scene coordinates
		LocalPoint local = new LocalPoint(param0, param1);
		if (client.isInInstancedRegion())
		{
			return WorldPoint.fromLocalInstance(client, local);
		}
		return WorldPoint.fromLocal(client, local);
	}
}
