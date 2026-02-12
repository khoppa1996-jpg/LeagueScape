package com.leaguescape.task;

import lombok.Value;

/**
 * One tile in the task grid for an area. Immutable.
 * id is e.g. "0,0" for center; tier 0 = center (free), tier 1+ = rings.
 */
@Value
public class TaskTile
{
	String id;
	int tier;
	String displayName;
	int points;
	int row;
	int col;

	public static String idFor(int row, int col)
	{
		return row + "," + col;
	}
}
