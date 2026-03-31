package com.leaguescape.worldunlock;

import lombok.Value;

/** A world-unlock tile placed at (row, col) on the grid. Center is (0, 0). */
@Value
public class WorldUnlockTilePlacement
{
	WorldUnlockTile tile;
	int row;
	int col;
}
