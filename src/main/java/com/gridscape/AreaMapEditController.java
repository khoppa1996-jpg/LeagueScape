package com.gridscape;

import com.gridscape.config.AreaEditState;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuEntryAdded;

/**
 * Shift+right-click tile menu entries when editing an area polygon (add/move/set corner).
 */
public final class AreaMapEditController
{
	private static final String ADD_CORNER_OPTION = "Add polygon corner";
	private static final String ADD_CORNER_TARGET = "Tile";
	private static final String MOVE_CORNER_OPTION = "Move";
	private static final String SET_CORNER_OPTION = "Set new corner";
	private static final String CANCEL_MOVE_OPTION = "Cancel move";

	private AreaMapEditController()
	{
	}

	/**
	 * @return true if the event was fully handled (caller should not add other entries for this path)
	 */
	public static boolean handleEditingMenu(GridScapePlugin plugin, Client client, AreaEditState areaEditState, MenuEntryAdded event)
	{
		if (!areaEditState.isEditingArea())
		{
			return false;
		}
		MenuAction action = event.getMenuEntry().getType();
		if (action != MenuAction.WALK && action != MenuAction.SET_HEADING)
		{
			return false;
		}
		int worldViewId = event.getMenuEntry().getWorldViewId();
		WorldView wv = client.getWorldView(worldViewId);
		if (wv == null)
		{
			wv = client.getTopLevelWorldView();
		}
		if (wv == null)
		{
			return false;
		}
		Tile tile = wv.getSelectedSceneTile();
		if (tile == null)
		{
			return false;
		}
		WorldPoint wp = GridScapePlugin.tileToWorldPoint(client, tile, wv);
		if (wp == null || !client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return false;
		}
		if (areaEditState.getMoveCornerIndex() >= 0)
		{
			client.createMenuEntry(-1)
				.setOption(SET_CORNER_OPTION)
				.setTarget(ADD_CORNER_TARGET)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> plugin.setCornerAtSelectedTile());
			client.createMenuEntry(-1)
				.setOption(CANCEL_MOVE_OPTION)
				.setTarget(ADD_CORNER_TARGET)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> {
					areaEditState.setMoveCornerIndex(-1);
					plugin.notifyCornersUpdated();
				});
		}
		else
		{
			int idx = plugin.findCornerAt(wp.getX(), wp.getY(), wp.getPlane());
			if (idx >= 0)
			{
				final int cornerIdx = idx;
				client.createMenuEntry(-1)
					.setOption(MOVE_CORNER_OPTION)
					.setTarget(ADD_CORNER_TARGET)
					.setType(MenuAction.RUNELITE)
					.onClick(e -> {
						areaEditState.setMoveCornerIndex(cornerIdx);
						plugin.notifyCornersUpdated();
					});
			}
			else
			{
				client.createMenuEntry(-1)
					.setOption(ADD_CORNER_OPTION)
					.setTarget(ADD_CORNER_TARGET)
					.setType(MenuAction.RUNELITE)
					.onClick(e -> plugin.addCornerAtSelectedTile());
			}
		}
		return true;
	}
}
