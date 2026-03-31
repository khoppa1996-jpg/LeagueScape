package com.leaguescape.util;

import com.leaguescape.task.TaskState;
import com.leaguescape.task.TaskTile;
import com.leaguescape.worldunlock.WorldUnlockService;
import com.leaguescape.worldunlock.WorldUnlockTilePlacement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cardinal fog-edge flags for frontier cells next to revealed-unclaimed neighbors. */
public final class FrontierFogHelpers
{
	private FrontierFogHelpers() {}

	/** Task grid: neighbor counts if REVEALED or COMPLETED_UNCLAIMED (not CLAIMED). */
	public static boolean isRevealedUnclaimedTaskState(TaskState state)
	{
		return state == TaskState.REVEALED || state == TaskState.COMPLETED_UNCLAIMED;
	}

	/**
	 * For a hidden cell at (row,col), sets north/east/south/west if the neighbor in that direction
	 * exists in {@code idToTile}, has {@code isRevealedUnclaimedTaskState(getState.apply(neighborId))}, and
	 * {@code includeNeighbor.test(neighborId)} (e.g. tile in grid list).
	 */
	public static boolean[] cardinalFlagsForHiddenCell(int row, int col,
		java.util.function.Function<String, TaskState> getState,
		java.util.function.Predicate<String> includeNeighbor)
	{
		boolean north = false, east = false, south = false, west = false;
		String[] ids = {
			TaskTile.idFor(row - 1, col),
			TaskTile.idFor(row, col + 1),
			TaskTile.idFor(row + 1, col),
			TaskTile.idFor(row, col - 1)
		};
		for (int i = 0; i < 4; i++)
		{
			String nid = ids[i];
			if (!includeNeighbor.test(nid)) continue;
			TaskState st = getState.apply(nid);
			if (!isRevealedUnclaimedTaskState(st)) continue;
			switch (i)
			{
				case 0: north = true; break;
				case 1: east = true; break;
				case 2: south = true; break;
				case 3: west = true; break;
				default: break;
			}
		}
		return new boolean[]{ north, east, south, west };
	}

	/** Build id -> TaskTile for positions present in grid. */
	public static Map<String, TaskTile> idMap(List<TaskTile> grid)
	{
		Map<String, TaskTile> m = new HashMap<>();
		for (TaskTile t : grid)
			if (t != null && t.getId() != null)
				m.put(t.getId(), t);
		return m;
	}

	/**
	 * World unlock: neighbor qualifies if revealed on grid and not claimed (includes unlocked-unclaimed and revealed-but-locked).
	 */
	public static boolean[] cardinalFlagsWorldUnlock(int row, int col,
		WorldUnlockService worldUnlockService, Set<String> claimed,
		List<WorldUnlockTilePlacement> gridPlacements)
	{
		Map<String, WorldUnlockTilePlacement> posToPlacement = new HashMap<>();
		for (WorldUnlockTilePlacement p : gridPlacements)
			posToPlacement.put(p.getRow() + "," + p.getCol(), p);

		boolean north = false, east = false, south = false, west = false;
		int[][] deltas = { { -1, 0 }, { 0, 1 }, { 1, 0 }, { 0, -1 } };
		for (int i = 0; i < 4; i++)
		{
			int nr = row + deltas[i][0], nc = col + deltas[i][1];
			String np = nr + "," + nc;
			WorldUnlockTilePlacement pl = posToPlacement.get(np);
			if (pl == null) continue;
			if (!worldUnlockService.isRevealed(pl, claimed, gridPlacements)) continue;
			String tid = pl.getTile().getId();
			if (claimed.contains(tid)) continue;
			switch (i)
			{
				case 0: north = true; break;
				case 1: east = true; break;
				case 2: south = true; break;
				case 3: west = true; break;
				default: break;
			}
		}
		return new boolean[]{ north, east, south, west };
	}
}
