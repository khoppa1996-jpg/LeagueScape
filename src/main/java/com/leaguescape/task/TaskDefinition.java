package com.leaguescape.task;

import lombok.Data;

/**
 * One task entry from tasks.json (displayName, taskType, difficulty, optional area).
 */
@Data
public class TaskDefinition
{
	private String displayName;
	private String taskType;
	/** 1 = easy (center), 5 = master (outer edge). */
	private int difficulty = 1;
	/** Optional area id/name; if set, this task only appears in that area's grid. Null or empty = any area. */
	private String area;
}
