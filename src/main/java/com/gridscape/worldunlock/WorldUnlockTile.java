package com.leaguescape.worldunlock;

import java.util.List;
import lombok.Data;

/**
 * One unlock tile from world_unlocks.json. type: "area" | "skill" | "quest" | "boss" | "achievement_diary" | "taskFilter".
 */
@Data
public class WorldUnlockTile
{
	private String type;
	private String id;
	private String displayName;
	private int tier;
	/** Optional; when absent, cost is computed as tier points × type multiplier in World Unlock mode. */
	private Integer cost;
	private List<String> prerequisites;
	private TaskLink taskLink;
}
