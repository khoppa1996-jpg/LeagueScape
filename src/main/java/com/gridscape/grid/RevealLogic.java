package com.gridscape.grid;

import java.util.Set;

/** Cardinal-neighbor reveal rules for task grids and world-unlock placement. */
public final class RevealLogic
{
	private RevealLogic() {}

	public static boolean revealedByClaimedPositions(int row, int col, Set<String> claimedNeighborPositions)
	{
		if (row == 0 && col == 0)
			return true;
		return claimedNeighborPositions != null && !claimedNeighborPositions.isEmpty();
	}

	public static boolean revealedByClaimedTaskIds(int row, int col, Set<String> claimedPositions)
	{
		if (row == 0 && col == 0)
			return true;
		if (claimedPositions == null || claimedPositions.isEmpty())
			return false;
		for (String n : GridPos.neighbors4(row, col))
		{
			if (claimedPositions.contains(n))
				return true;
		}
		return false;
	}
}
