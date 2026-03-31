package com.gridscape.worldunlock;

import java.util.List;
import lombok.Data;

/** Root object for world_unlocks.json: _schema and unlocks array. */
@Data
public class WorldUnlocksData
{
	private String _schema;
	private List<WorldUnlockTile> unlocks;
}
