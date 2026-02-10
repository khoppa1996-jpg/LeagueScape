package com.leaguescape.area;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.leaguescape.data.Area;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * Loads areas from areas.json and provides unlocked set, neighbors, and point-in-area checks.
 */
@Slf4j
@Singleton
public class AreaGraphService
{
	private static final String AREAS_RESOURCE = "areas.json";

	private List<Area> areas = new ArrayList<>();
	private final Set<String> unlockedAreaIds = new HashSet<>();

	@Inject
	public AreaGraphService()
	{
		loadAreas();
	}

	private void loadAreas()
	{
		try (InputStream is = getClass().getResourceAsStream("/" + AREAS_RESOURCE))
		{
			if (is == null)
			{
				log.warn("areas.json not found in resources");
				return;
			}
			areas = new Gson().fromJson(
				new InputStreamReader(is, StandardCharsets.UTF_8),
				new TypeToken<List<Area>>() { }.getType());
			if (areas == null)
			{
				areas = new ArrayList<>();
			}
			log.debug("Loaded {} areas", areas.size());
		}
		catch (Exception e)
		{
			log.error("Failed to load areas.json", e);
			areas = new ArrayList<>();
		}
	}

	public void setUnlockedAreaIds(Set<String> ids)
	{
		unlockedAreaIds.clear();
		if (ids != null)
		{
			unlockedAreaIds.addAll(ids);
		}
	}

	public void addUnlocked(String areaId)
	{
		unlockedAreaIds.add(areaId);
	}

	public Set<String> getUnlockedAreaIds()
	{
		return Collections.unmodifiableSet(unlockedAreaIds);
	}

	public boolean isWorldPointUnlocked(WorldPoint worldPoint)
	{
		int regionId = (worldPoint.getX() >> 6) << 8 | (worldPoint.getY() >> 6);
		for (String areaId : unlockedAreaIds)
		{
			Area area = getArea(areaId);
			if (area == null) continue;
			// Check surface polygon regions + includes
			if (area.getIncludes() != null && area.getIncludes().contains(regionId))
			{
				return true;
			}
			if (area.getPolygon() != null && pointInPolygon(worldPoint, area.getPolygon()))
			{
				return true;
			}
		}
		return false;
	}

	private boolean pointInPolygon(WorldPoint p, List<int[]> polygon)
	{
		if (polygon == null || polygon.size() < 3) return false;
		int x = p.getX(), y = p.getY();
		int n = polygon.size();
		boolean inside = false;
		for (int i = 0, j = n - 1; i < n; j = i++)
		{
			int[] vi = polygon.get(i);
			int[] vj = polygon.get(j);
			int xi = vi[0], yi = vi[1];
			int xj = vj[0], yj = vj[1];
			if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi))
			{
				inside = !inside;
			}
		}
		return inside;
	}

	public List<Area> getUnlockableNeighbors()
	{
		Set<String> neighborIds = new HashSet<>();
		for (String areaId : unlockedAreaIds)
		{
			Area area = getArea(areaId);
			if (area != null && area.getNeighbors() != null)
			{
				for (String n : area.getNeighbors())
				{
					if (!unlockedAreaIds.contains(n))
					{
						neighborIds.add(n);
					}
				}
			}
		}
		return neighborIds.stream()
			.map(this::getArea)
			.filter(a -> a != null)
			.collect(Collectors.toList());
	}

	public int getCost(String areaId)
	{
		Area area = getArea(areaId);
		return area != null ? area.getUnlockCost() : 0;
	}

	public Area getArea(String areaId)
	{
		for (Area a : areas)
		{
			if (a.getId().equals(areaId)) return a;
		}
		return null;
	}

	public List<Area> getAreas()
	{
		return Collections.unmodifiableList(areas);
	}
}