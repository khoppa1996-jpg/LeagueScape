package com.leaguescape.data;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * One unlockable area (city or region). Polygon is list of [x, y, plane] world points.
 * includes = region IDs for dungeons/interiors; neighbors = adjacent area IDs.
 */
@Value
@Builder
public class Area
{
	String id;
	String displayName;
	List<int[]> polygon;       // {x, y, plane} per point
	List<Integer> includes;   // region IDs (surface + interiors)
	List<String> neighbors;   // adjacent area ids
	int unlockCost;
}
