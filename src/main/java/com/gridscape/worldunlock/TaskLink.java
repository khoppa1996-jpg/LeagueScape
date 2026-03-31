package com.gridscape.worldunlock;

import java.util.List;
import lombok.Data;

/**
 * taskLink object from world_unlocks.json. Describes how to match tasks from tasks.json
 * when this unlock tile is unlocked. type: "area" | "skill" | "taskFilter" | "taskDisplayNames".
 */
@Data
public class TaskLink
{
	private String type;
	// skill
	private String skillName;
	private Integer levelMin;
	private Integer levelMax;
	// taskFilter
	private String taskType;
	private String requirementsContains;
	private Integer difficulty;
	// taskDisplayNames
	private List<String> taskDisplayNames;
}
