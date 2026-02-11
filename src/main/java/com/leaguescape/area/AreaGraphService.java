package com.leaguescape.area;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.leaguescape.data.Area;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;

/**
 * Loads areas from areas.json and custom areas from config. Merges them and provides
 * unlocked set, neighbors, and point-in-area checks.
 */
@Slf4j
@Singleton
public class AreaGraphService
{
	private static final String AREAS_RESOURCE = "areas.json";
	private static final String CONFIG_GROUP = "leaguescapeConfig";
	private static final String KEY_CUSTOM_AREAS = "customAreas";
	private static final String KEY_REMOVED_AREAS = "removedAreas";

	private static final Gson GSON_PRETTY = new GsonBuilder().setPrettyPrinting().create();

	private static final Gson GSON = new GsonBuilder()
		.registerTypeAdapter(int[].class, new JsonDeserializer<int[]>()
		{
			@Override
			public int[] deserialize(JsonElement json, Type type, JsonDeserializationContext context) throws JsonParseException
			{
				JsonArray arr = json.getAsJsonArray();
				int[] result = new int[arr.size()];
				for (int i = 0; i < arr.size(); i++)
				{
					result[i] = arr.get(i).getAsInt();
				}
				return result;
			}
		})
		.create();

	private final ConfigManager configManager;

	private volatile List<Area> areas = new ArrayList<>();
	private final Set<String> unlockedAreaIds = new HashSet<>();

	@Inject
	public AreaGraphService(ConfigManager configManager)
	{
		this.configManager = configManager;
		reloadAreas();
	}

	/** Reload built-in areas from JSON and custom areas from config. Custom overrides built-in by id. */
	public void reloadAreas()
	{
		List<Area> builtIn = loadBuiltInAreas();
		Set<String> removed = loadRemovedAreaIds();
		List<Area> custom = loadCustomAreas();
		areas = new ArrayList<>(builtIn);
		areas.removeIf(a -> removed.contains(a.getId()));
		for (Area c : custom)
		{
			areas.removeIf(a -> a.getId().equals(c.getId()));
			areas.add(c);
		}
		log.debug("Loaded {} areas ({} built-in, {} removed, {} custom)", areas.size(), builtIn.size(), removed.size(), custom.size());
	}

	private List<Area> loadBuiltInAreas()
	{
		try (InputStream is = getClass().getResourceAsStream("/" + AREAS_RESOURCE))
		{
			if (is == null)
			{
				log.warn("areas.json not found in resources");
				return new ArrayList<>();
			}
			List<Area> list = GSON.fromJson(
				new InputStreamReader(is, StandardCharsets.UTF_8),
				new TypeToken<List<Area>>() { }.getType());
			return list != null ? list : new ArrayList<>();
		}
		catch (Exception e)
		{
			log.error("Failed to load areas.json", e);
			return new ArrayList<>();
		}
	}

	private List<Area> loadCustomAreas()
	{
		String raw = configManager.getConfiguration(CONFIG_GROUP, KEY_CUSTOM_AREAS);
		if (raw == null || raw.isEmpty()) return new ArrayList<>();
		try
		{
			List<Area> list = GSON.fromJson(raw, new TypeToken<List<Area>>() { }.getType());
			return list != null ? list : new ArrayList<>();
		}
		catch (Exception e)
		{
			log.error("Failed to parse custom areas", e);
			return new ArrayList<>();
		}
	}

	private Set<String> loadRemovedAreaIds()
	{
		String raw = configManager.getConfiguration(CONFIG_GROUP, KEY_REMOVED_AREAS);
		if (raw == null || raw.isEmpty()) return new HashSet<>();
		try
		{
			List<String> list = GSON.fromJson(raw, new TypeToken<List<String>>() { }.getType());
			return list != null ? new HashSet<>(list) : new HashSet<>();
		}
		catch (Exception e)
		{
			log.error("Failed to parse removed areas", e);
			return new HashSet<>();
		}
	}

	private void persistRemovedAreaIds(Set<String> ids)
	{
		String json = GSON.toJson(new ArrayList<>(ids));
		configManager.setConfiguration(CONFIG_GROUP, KEY_REMOVED_AREAS, json);
	}

	/**
	 * Save or update a custom area. Computes includes from polygon region IDs.
	 * Merges with existing custom areas and persists.
	 */
	public void saveCustomArea(Area area)
	{
		Area withIncludes = Area.builder()
			.id(area.getId())
			.displayName(area.getDisplayName())
			.polygon(area.getPolygon())
			.includes(computeIncludesFromPolygon(area.getPolygon()))
			.neighbors(area.getNeighbors() != null ? area.getNeighbors() : Collections.emptyList())
			.unlockCost(area.getUnlockCost())
			.build();

		List<Area> custom = new ArrayList<>(loadCustomAreas());
		custom.removeIf(a -> a.getId().equals(withIncludes.getId()));
		custom.add(withIncludes);
		persistCustomAreas(custom);
		reloadAreas();
	}

	/**
	 * Remove an area from the list. Custom areas are removed from config.
	 * Built-in areas are added to the removed list (hidden but can be restored).
	 */
	public void removeArea(String areaId)
	{
		if (areaId == null) return;
		if (loadCustomAreas().stream().anyMatch(a -> a.getId().equals(areaId)))
		{
			List<Area> custom = new ArrayList<>(loadCustomAreas());
			custom.removeIf(a -> a.getId().equals(areaId));
			persistCustomAreas(custom);
		}
		else if (loadBuiltInAreas().stream().anyMatch(a -> a.getId().equals(areaId)))
		{
			Set<String> removed = new HashSet<>(loadRemovedAreaIds());
			removed.add(areaId);
			persistRemovedAreaIds(removed);
		}
		reloadAreas();
	}

	/** Restore a previously removed built-in area. */
	public void restoreArea(String areaId)
	{
		if (areaId == null) return;
		Set<String> removed = new HashSet<>(loadRemovedAreaIds());
		removed.remove(areaId);
		persistRemovedAreaIds(removed);
		reloadAreas();
	}

	/** True if the area was removed (built-in area hidden by user). */
	public boolean isRemovedArea(String areaId)
	{
		return loadRemovedAreaIds().contains(areaId);
	}

	/** Ids of removed built-in areas (for UI restore list). */
	public Set<String> getRemovedAreaIds()
	{
		return Collections.unmodifiableSet(loadRemovedAreaIds());
	}

	/** Export current areas (built-in minus removed, plus custom) as pretty-printed JSON. */
	public String exportAreasToJson()
	{
		return GSON_PRETTY.toJson(areas);
	}

	/**
	 * Parse and validate JSON, then replace custom areas with the imported list.
	 * Validates: root is array, each area has id, polygon (3+ points, each [x,y,plane]), and valid structure.
	 * @return the number of areas imported
	 * @throws IllegalArgumentException with a descriptive message if JSON is invalid or validation fails
	 */
	public int importCustomAreasFromJson(String json)
	{
		if (json == null || json.trim().isEmpty())
		{
			throw new IllegalArgumentException("JSON is empty.");
		}
		List<Area> list;
		try
		{
			list = GSON.fromJson(json, new TypeToken<List<Area>>() { }.getType());
		}
		catch (JsonParseException e)
		{
			throw new IllegalArgumentException("Invalid JSON: " + e.getMessage());
		}
		if (list == null)
		{
			throw new IllegalArgumentException("JSON root must be an array of area objects.");
		}
		Set<String> seenIds = new HashSet<>();
		for (int i = 0; i < list.size(); i++)
		{
			Area a = list.get(i);
			if (a == null)
			{
				throw new IllegalArgumentException("Area at index " + i + " is null.");
			}
			if (a.getId() == null || a.getId().trim().isEmpty())
			{
				throw new IllegalArgumentException("Area at index " + i + " has missing or empty \"id\".");
			}
			if (seenIds.contains(a.getId()))
			{
				throw new IllegalArgumentException("Duplicate area id: \"" + a.getId() + "\".");
			}
			seenIds.add(a.getId());
			if (a.getPolygon() == null)
			{
				throw new IllegalArgumentException("Area \"" + a.getId() + "\" has missing \"polygon\".");
			}
			if (a.getPolygon().size() < 3)
			{
				throw new IllegalArgumentException("Area \"" + a.getId() + "\" must have at least 3 polygon corners (has " + a.getPolygon().size() + ").");
			}
			for (int j = 0; j < a.getPolygon().size(); j++)
			{
				int[] pt = a.getPolygon().get(j);
				if (pt == null || pt.length < 3)
				{
					throw new IllegalArgumentException("Area \"" + a.getId() + "\" polygon point " + j + " must be [x, y, plane] (3 numbers).");
				}
				int plane = pt[2];
				if (plane < 0 || plane > 3)
				{
					throw new IllegalArgumentException("Area \"" + a.getId() + "\" polygon point " + j + " has invalid plane " + plane + " (must be 0-3).");
				}
			}
			if (a.getUnlockCost() < 0)
			{
				throw new IllegalArgumentException("Area \"" + a.getId() + "\" has invalid unlockCost (must be >= 0).");
			}
			if (a.getNeighbors() != null)
			{
				for (String n : a.getNeighbors())
				{
					if (n == null || n.trim().isEmpty())
					{
						throw new IllegalArgumentException("Area \"" + a.getId() + "\" has null or empty neighbor entry.");
					}
				}
			}
		}
		List<Area> withIncludes = new ArrayList<>();
		for (Area a : list)
		{
			Area normalized = Area.builder()
				.id(a.getId())
				.displayName(a.getDisplayName() != null ? a.getDisplayName() : a.getId())
				.polygon(a.getPolygon())
				.includes(a.getIncludes() != null && !a.getIncludes().isEmpty() ? a.getIncludes() : computeIncludesFromPolygon(a.getPolygon()))
				.neighbors(a.getNeighbors() != null ? a.getNeighbors() : Collections.emptyList())
				.unlockCost(a.getUnlockCost())
				.build();
			withIncludes.add(normalized);
		}
		persistCustomAreas(withIncludes);
		reloadAreas();
		log.info("Imported {} areas from JSON.", withIncludes.size());
		return withIncludes.size();
	}

	/** Check if an area is a built-in area (from areas.json). */
	public boolean isBuiltInArea(String areaId)
	{
		return loadBuiltInAreas().stream().anyMatch(a -> a.getId().equals(areaId));
	}

	/** Get a built-in area by id (for display name of removed areas). */
	public Area getBuiltInArea(String areaId)
	{
		return loadBuiltInAreas().stream()
			.filter(a -> a.getId().equals(areaId))
			.findFirst()
			.orElse(null);
	}

	private void persistCustomAreas(List<Area> custom)
	{
		String json = GSON.toJson(custom);
		configManager.setConfiguration(CONFIG_GROUP, KEY_CUSTOM_AREAS, json);
	}

	/**
	 * Compute all region IDs covered by the polygon (for includes).
	 * Samples the polygon bounding box to ensure full coverage, not just vertices.
	 */
	private List<Integer> computeIncludesFromPolygon(List<int[]> polygon)
	{
		if (polygon == null || polygon.isEmpty()) return Collections.emptyList();
		Set<Integer> ids = new HashSet<>();
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
		for (int[] v : polygon)
		{
			int x = v[0], y = v[1];
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
			minY = Math.min(minY, y);
			maxY = Math.max(maxY, y);
		}
		// Add all regions in the bounding box; sample at region centers (every 64 tiles)
		for (int rx = (minX >> 6) << 6; rx <= maxX; rx += 64)
		{
			for (int ry = (minY >> 6) << 6; ry <= maxY; ry += 64)
			{
				int cx = rx + 32;
				int cy = ry + 32;
				if (pointInPolygonRaw(cx, cy, polygon))
				{
					int regionId = (cx >> 6) << 8 | (cy >> 6);
					ids.add(regionId);
				}
			}
		}
		// Also add vertex regions (for small polygons that might miss the sampling)
		for (int[] v : polygon)
		{
			int regionId = (v[0] >> 6) << 8 | (v[1] >> 6);
			ids.add(regionId);
		}
		return new ArrayList<>(ids);
	}

	private boolean pointInPolygonRaw(int x, int y, List<int[]> polygon)
	{
		if (polygon == null || polygon.size() < 3) return false;
		int n = polygon.size();
		boolean inside = false;
		for (int i = 0, j = n - 1; i < n; j = i++)
		{
			int[] vi = polygon.get(i);
			int[] vj = polygon.get(j);
			int xi = vi[0], yi = vi[1];
			int xj = vj[0], yj = vj[1];
			if (yi == yj) continue;
			if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi))
			{
				inside = !inside;
			}
		}
		return inside;
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

	/** True if the point is inside any area's polygon. */
	public boolean isWorldPointInAnyArea(WorldPoint worldPoint)
	{
		for (Area a : areas)
		{
			if (a.getPolygon() != null && pointInPolygon(worldPoint, a.getPolygon()))
			{
				return true;
			}
		}
		return false;
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
		int x = p.getX(), y = p.getY(), plane = p.getPlane();
		int n = polygon.size();
		// Plane must match: polygon vertices use [x, y, plane]; if present, enforce
		int polyPlane = polygon.get(0).length >= 3 ? polygon.get(0)[2] : 0;
		if (plane != polyPlane) return false;

		boolean inside = false;
		for (int i = 0, j = n - 1; i < n; j = i++)
		{
			int[] vi = polygon.get(i);
			int[] vj = polygon.get(j);
			int xi = vi[0], yi = vi[1];
			int xj = vj[0], yj = vj[1];
			if (yi == yj) continue; // skip horizontal edge to avoid division by zero
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

	/**
	 * Returns boundary edges (corner-to-corner) that separate locked from unlocked areas.
	 * Edges between two unlocked areas are excluded. Each edge is {x1,y1,plane,x2,y2} (plane shared).
	 */
	public List<int[]> getBoundaryEdges(int plane)
	{
		Set<String> unlocked = getUnlockedAreaIds();
		Map<String, Set<String>> edgeToAreas = new HashMap<>();

		for (Area area : areas)
		{
			List<int[]> poly = area.getPolygon();
			if (poly == null || poly.size() < 3) continue;

			int n = poly.size();
			for (int i = 0; i < n; i++)
			{
				int[] v1 = poly.get(i);
				int[] v2 = poly.get((i + 1) % n);
				if (v1.length < 3 || v2.length < 3) continue;
				if (v1[2] != plane || v2[2] != plane) continue;

				String key = edgeKey(v1[0], v1[1], v2[0], v2[1]);
				edgeToAreas.computeIfAbsent(key, k -> new HashSet<>()).add(area.getId());
			}
		}

		Set<String> added = new HashSet<>();
		List<int[]> result = new ArrayList<>();
		for (Area area : areas)
		{
			List<int[]> poly = area.getPolygon();
			if (poly == null || poly.size() < 3) continue;

			int n = poly.size();
			for (int i = 0; i < n; i++)
			{
				int[] v1 = poly.get(i);
				int[] v2 = poly.get((i + 1) % n);
				if (v1.length < 3 || v2.length < 3) continue;
				if (v1[2] != plane || v2[2] != plane) continue;

				String key = edgeKey(v1[0], v1[1], v2[0], v2[1]);
				if (added.contains(key)) continue;

				Set<String> areaIds = edgeToAreas.get(key);
				if (areaIds == null) continue;

				// Skip if both neighboring areas are unlocked
				boolean allUnlocked = areaIds.stream().allMatch(unlocked::contains);
				if (allUnlocked) continue;

				// Draw: boundary between locked and unlocked, or external edge of locked area
				added.add(key);
				result.add(new int[]{v1[0], v1[1], v1[2], v2[0], v2[1]});
			}
		}
		return result;
	}

	/**
	 * Returns edge keys for boundaries between locked and unlocked areas only.
	 * These edges must not be extended to the viewport (would render over unlocked).
	 * Excludes edges that border only locked areas or external edges.
	 */
	public Set<String> getBoundaryEdgeKeys(int plane)
	{
		Set<String> unlocked = getUnlockedAreaIds();
		Map<String, Set<String>> edgeToAreas = new HashMap<>();

		for (Area area : areas)
		{
			List<int[]> poly = area.getPolygon();
			if (poly == null || poly.size() < 3) continue;

			int n = poly.size();
			for (int i = 0; i < n; i++)
			{
				int[] v1 = poly.get(i);
				int[] v2 = poly.get((i + 1) % n);
				if (v1.length < 3 || v2.length < 3) continue;
				if (v1[2] != plane || v2[2] != plane) continue;

				String key = edgeKey(v1[0], v1[1], v2[0], v2[1]);
				edgeToAreas.computeIfAbsent(key, k -> new HashSet<>()).add(area.getId());
			}
		}

		Set<String> result = new HashSet<>();
		for (Map.Entry<String, Set<String>> e : edgeToAreas.entrySet())
		{
			Set<String> areaIds = e.getValue();
			boolean hasLocked = areaIds.stream().anyMatch(id -> !unlocked.contains(id));
			boolean hasUnlocked = areaIds.stream().anyMatch(unlocked::contains);
			if (hasLocked && hasUnlocked)
			{
				result.add(e.getKey());
			}
		}
		return result;
	}

	private static String edgeKey(int x1, int y1, int x2, int y2)
	{
		if (x1 < x2 || (x1 == x2 && y1 <= y2))
		{
			return x1 + "," + y1 + "," + x2 + "," + y2;
		}
		return x2 + "," + y2 + "," + x1 + "," + y1;
	}
}