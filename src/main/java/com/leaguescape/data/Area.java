package com.leaguescape.data;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * One unlockable area (city or region). May have multiple polygons (e.g. main land + island).
 * Each polygon is a list of [x, y, plane] world points. includes = region IDs for dungeons/interiors; neighbors = adjacent area IDs.
 * unlockCost = points to spend to unlock this area. pointsToComplete = points to earn in this area to "complete" it (null = use unlockCost).
 */
@Value
@Builder
public class Area
{
	String id;
	String displayName;
	/** Optional description shown in the Area Details popup on the world map. */
	String description;
	/** One or more polygons; each polygon is list of [x, y, plane] corners. */
	List<List<int[]>> polygons;
	/** Holes (subtracted from the union of polygons). Each hole is a list of [x, y, plane]. Used e.g. to cut islands out of an ocean. */
	List<List<int[]>> holes;
	List<Integer> includes;   // region IDs (surface + interiors)
	List<String> neighbors;   // adjacent area ids
	int unlockCost;
	/** Points to earn in this area to complete it (for points-to-complete mode). If null, unlockCost is used. */
	Integer pointsToComplete;

	/**
	 * Returns the first polygon for this area, for callers that only need a single polygon.
	 * Used when drawing or testing point-in-area with a single outline.
	 *
	 * @return the first polygon (list of [x, y, plane] points), or null if this area has no polygons
	 */
	public List<int[]> getPolygon()
	{
		return (polygons != null && !polygons.isEmpty()) ? polygons.get(0) : null;
	}
}
