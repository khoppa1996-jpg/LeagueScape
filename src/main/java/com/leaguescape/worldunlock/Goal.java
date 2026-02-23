package com.leaguescape.worldunlock;

import lombok.Data;

/**
 * One goal from goals.json. conditionType: e.g. "world_unlock", "skill_level", "quest_complete".
 * Condition-specific fields (e.g. worldUnlockId) determine when the goal is complete.
 */
@Data
public class Goal
{
	private String id;
	private String displayName;
	private String description;
	private String conditionType;
	private String worldUnlockId;
}
