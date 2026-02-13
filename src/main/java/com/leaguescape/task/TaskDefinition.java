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

	/** Area ids this task is limited to. Empty = appears in any area. Non-empty = appears only in these areas' grids; task is "mystery" until all are unlocked. */
	public List<String> getRequiredAreaIds()
	{
		if (areas != null && !areas.isEmpty())
			return areas;
		if (area != null && !area.trim().isEmpty())
			return Collections.singletonList(area.trim());
		return Collections.emptyList();
	}
}
