package com.leaguescape.task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * Root structure for tasks.json. Holds the default task list used for all areas, plus optional
 * per-area overrides. When building the grid for an area, the loader checks {@link #areas} first;
 * if that area has an override, those tasks are used; otherwise {@link #defaultTasks} are filtered
 * by area (via each task's area/areas field).
 */
@Data
public class TasksData
{
	/** Default tasks for areas that do not have an entry in {@link #areas}. */
	private List<TaskDefinition> defaultTasks = new ArrayList<>();

	/**
	 * Optional per-area overrides. Key = area ID, value = task list for that area.
	 * If an area ID is present here, its tasks replace the default list for that area only.
	 */
	private Map<String, AreaTasks> areas = new HashMap<>();

	/**
	 * Per-area task list. Used as the value type for {@link TasksData#areas}.
	 */
	@Data
	public static class AreaTasks
	{
		private List<TaskDefinition> tasks = new ArrayList<>();
	}
}
