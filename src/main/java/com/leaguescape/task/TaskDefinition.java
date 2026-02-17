package com.leaguescape.task;

import java.util.Collections;
import java.util.List;
import lombok.Data;

/**
 * One task entry from tasks.json (displayName, taskType, difficulty, optional area/areas).
 * "area" may be a single string or an array of strings; task appears in each listed area's grid
 * and is only fully revealed (non-mystery) when all listed areas are unlocked.
 */
@Data
public class TaskDefinition
{
	private String displayName;
	private String taskType;
	/** 1 = easy (center), 5 = master (outer edge). */
	private int difficulty = 1;
	/** Optional single area id; used when "area" is a string in JSON. */
	private String area;
	/** Optional list of area ids; used when "area" is an array. Task appears in each area's grid and is mystery until all are unlocked. */
	private List<String> areas;
	/** When true, task is available in Free to Play worlds. In Members mode all tasks (including f2p) are available. */
	private Boolean f2p;

	/**
	 * Returns the list of area IDs this task is restricted to. Used to filter which area grids show this task
	 * and to determine if the task is shown as a "mystery" (when not all required areas are unlocked).
	 *
	 * @return non-empty list of area IDs if task is area-specific; empty list if task appears in any area
	 */
	public List<String> getRequiredAreaIds()
	{
		if (areas != null && !areas.isEmpty())
			return areas;
		if (area != null && !area.trim().isEmpty())
			return Collections.singletonList(area.trim());
		return Collections.emptyList();
	}
}
