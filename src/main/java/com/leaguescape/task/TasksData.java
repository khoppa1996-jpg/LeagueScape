package com.leaguescape.task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * Root structure for tasks.json: default task list and optional per-area overrides.
 */
@Data
public class TasksData
{
	private List<TaskDefinition> defaultTasks = new ArrayList<>();
	/** Optional: areaId -> { tasks: [...] }. If an area is present, use its tasks instead of defaultTasks. */
	private Map<String, AreaTasks> areas = new HashMap<>();

	@Data
	public static class AreaTasks
	{
		private List<TaskDefinition> tasks = new ArrayList<>();
	}
}
