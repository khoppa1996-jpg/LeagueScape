package com.leaguescape.data;

/**
 * Runtime status of an area when using LeagueScape.
 * Areas defined in areas.json can be in one of these states during play.
 */
public enum AreaStatus
{
	/** Locked: cannot be interacted with in the game window. */
	LOCKED,

	/** Unlocked: available for interaction and has tasks, but not fully completed yet. */
	UNLOCKED,

	/** Complete: fully unlocked; may still have tasks remaining to earn more points for other areas. */
	COMPLETE
}
