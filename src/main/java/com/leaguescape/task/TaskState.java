package com.leaguescape.task;

/**
 * Per-area, per-task state in the task grid.
 * LOCKED = not yet revealed (adjacent task not claimed).
 * REVEALED = visible, not completed.
 * COMPLETED_UNCLAIMED = completed, awaiting Claim to reveal neighbors and award points.
 * CLAIMED = claimed; neighbors are revealed and points awarded.
 */
public enum TaskState
{
	LOCKED,
	REVEALED,
	COMPLETED_UNCLAIMED,
	CLAIMED
}
