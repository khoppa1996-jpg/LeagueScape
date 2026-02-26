package com.leaguescape.worldunlock;

import com.google.gson.Gson;
import com.leaguescape.area.AreaGraphService;
import com.leaguescape.data.Area;
import com.leaguescape.points.PointsService;
import com.leaguescape.task.TaskDefinition;
import com.leaguescape.task.TaskGridService;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads world_unlocks.json, persists unlocked tile ids, and provides unlock(tileId, cost).
 * Used only when unlock mode is WORLD_UNLOCK.
 */
@Singleton
public class WorldUnlockService
{
	private static final Logger log = LoggerFactory.getLogger(WorldUnlockService.class);
	private static final String STATE_GROUP = "leaguescapeState";
	private static final String KEY_WORLD_UNLOCK_UNLOCKED_IDS = "worldUnlockUnlockedIds";
	private static final String KEY_WORLD_UNLOCK_GRID_SEED = "worldUnlockGridSeed";
	private static final String KEY_WORLD_UNLOCK_GRID_STATE = "worldUnlockGridState";
	private static final String WORLD_UNLOCKS_RESOURCE = "/world_unlocks.json";
	/** Grid state entry format: pos##tileId (same idea as Global Task grid). */
	private static final String GRID_STATE_SEP = "##";
	private static final String POS_ENTRY_SEP = "||";

	private final ConfigManager configManager;
	private final PointsService pointsService;
	private final TaskGridService taskGridService;
	private final AreaGraphService areaGraphService;

	private List<WorldUnlockTile> tiles = new ArrayList<>();
	private final Set<String> unlockedIds = new HashSet<>();
	private boolean loaded = false;

	@Inject
	public WorldUnlockService(ConfigManager configManager, PointsService pointsService, TaskGridService taskGridService,
		AreaGraphService areaGraphService)
	{
		this.configManager = configManager;
		this.pointsService = pointsService;
		this.taskGridService = taskGridService;
		this.areaGraphService = areaGraphService;
	}

	/** Load tiles from resource and persisted unlocked ids. Call once at startup when mode is WORLD_UNLOCK. */
	public void load()
	{
		if (loaded)
		{
			return;
		}
		tiles.clear();
		unlockedIds.clear();
		Gson gson = new Gson();
		try (InputStream in = getClass().getResourceAsStream(WORLD_UNLOCKS_RESOURCE))
		{
			if (in == null)
			{
				log.warn("World unlocks resource not found: {}", WORLD_UNLOCKS_RESOURCE);
				loaded = true;
				return;
			}
			WorldUnlocksData data = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), WorldUnlocksData.class);
			if (data != null && data.getUnlocks() != null)
			{
				tiles = data.getUnlocks();
			}
		}
		catch (Exception e)
		{
			log.error("Failed to load world_unlocks.json", e);
		}
		String raw = configManager.getConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_UNLOCKED_IDS);
		if (raw != null && !raw.isEmpty())
		{
			for (String id : raw.split(","))
			{
				String t = id.trim();
				if (!t.isEmpty())
				{
					unlockedIds.add(t);
				}
			}
		}
		loaded = true;
	}

	private void persistUnlocked()
	{
		String joined = String.join(",", unlockedIds);
		configManager.setConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_UNLOCKED_IDS, joined);
	}

	private static int[] parsePos(String pos)
	{
		if (pos == null) return null;
		String[] p = pos.split(",");
		if (p.length != 2) return null;
		try
		{
			return new int[]{ Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()) };
		}
		catch (NumberFormatException e) { return null; }
	}

	private static String normalizePos(String pos)
	{
		int[] rc = parsePos(pos);
		return rc != null ? (rc[0] + "," + rc[1]) : (pos != null ? pos.trim() : "");
	}

	private static List<String> getNeighborPositions(String pos)
	{
		int[] rc = parsePos(pos);
		if (rc == null) return new ArrayList<>();
		int r = rc[0], c = rc[1];
		List<String> out = new ArrayList<>(4);
		out.add((r + 1) + "," + c);
		out.add((r - 1) + "," + c);
		out.add(r + "," + (c + 1));
		out.add(r + "," + (c - 1));
		return out;
	}

	/** Loads grid state: normalized position -> tile id. Only add when a position is first revealed; never overwrite. */
	private Map<String, String> loadGridState()
	{
		String raw = configManager.getConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_GRID_STATE);
		Map<String, String> gridState = new HashMap<>();
		if (raw == null || raw.isEmpty()) return gridState;
		Pattern entrySplit = Pattern.compile("\\|\\|(?!\\|)");
		for (String entry : entrySplit.split(raw))
		{
			entry = entry.trim();
			if (entry.isEmpty() || !entry.contains(GRID_STATE_SEP)) continue;
			int i = entry.indexOf(GRID_STATE_SEP);
			String pos = entry.substring(0, i).trim();
			String tileId = entry.substring(i + GRID_STATE_SEP.length()).trim();
			if (!pos.isEmpty() && !tileId.isEmpty())
				gridState.put(normalizePos(pos), tileId);
		}
		return gridState;
	}

	private void saveGridState(Map<String, String> gridState)
	{
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, String> e : gridState.entrySet())
		{
			String pos = e.getKey();
			String tileId = e.getValue();
			if (pos != null && !pos.isEmpty() && tileId != null && !tileId.isEmpty())
				parts.add(pos + GRID_STATE_SEP + tileId);
		}
		configManager.setConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_GRID_STATE, String.join(POS_ENTRY_SEP, parts));
	}

	/** Returns the grid seed used for shuffle order when assigning new tiles. */
	public int getGridSeed()
	{
		String raw = configManager.getConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_GRID_SEED);
		if (raw == null || raw.isEmpty()) return 0;
		try { return Integer.parseInt(raw); } catch (NumberFormatException e) { return 0; }
	}

	/** Increments the grid seed so the next getGrid() produces a new layout (e.g. on reset). */
	public void incrementGridSeed()
	{
		configManager.setConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_GRID_SEED, String.valueOf(getGridSeed() + 1));
	}

	/**
	 * Returns the world unlock grid using rolling assignment (same model as Global Task grid).
	 * Center (0,0) = start tile. Tiles are assigned only when first revealed (adjacent to an unlocked tile).
	 * Never reassign; prerequisites must be placed or unlocked before an unlock can be rolled.
	 * Skill levels 1-10 are prioritized for ring 1 and ring 2.
	 */
	public List<WorldUnlockTilePlacement> getGrid()
	{
		if (!loaded) load();
		List<WorldUnlockTile> all = new ArrayList<>(tiles);
		if (all.isEmpty()) return Collections.emptyList();

		WorldUnlockTile centerTile = null;
		for (WorldUnlockTile t : all)
		{
			if ((t.getPrerequisites() == null || t.getPrerequisites().isEmpty()) && t.getCost() == 0)
			{
				centerTile = t;
				break;
			}
		}
		if (centerTile == null)
			centerTile = all.get(0);

		// 1. Single grid state: position -> tile id. Only add when first revealed; never overwrite.
		Map<String, String> gridState = new HashMap<>(loadGridState());

		// 2. Unlocked positions = positions whose assigned tile has been unlocked by the user
		Set<String> unlockedPositions = new HashSet<>();
		unlockedPositions.add("0,0"); // center counts as unlocked for reveal
		for (Map.Entry<String, String> e : gridState.entrySet())
		{
			if (unlockedIds.contains(e.getValue()))
				unlockedPositions.add(e.getKey());
		}

		// 3. Revealed = center + unlocked positions + neighbors of unlocked positions
		Set<String> revealedPositions = new HashSet<>();
		revealedPositions.add("0,0");
		for (String up : unlockedPositions)
		{
			revealedPositions.add(normalizePos(up));
			revealedPositions.addAll(getNeighborPositions(up));
		}

		// 4. toAssign = revealed positions that have no assignment yet (first time revealed)
		Set<String> placedIds = new HashSet<>(gridState.values());
		List<String> toAssign = new ArrayList<>();
		for (String pos : revealedPositions)
		{
			if ("0,0".equals(pos)) continue;
			if (!gridState.containsKey(normalizePos(pos)))
				toAssign.add(normalizePos(pos));
		}

		// 5. Available = tiles (not center) not yet placed, with prerequisites satisfied (unlocked or already placed)
		Set<String> satisfiedIds = new HashSet<>(unlockedIds);
		satisfiedIds.addAll(placedIds);
		List<WorldUnlockTile> available = new ArrayList<>();
		for (WorldUnlockTile t : all)
		{
			if (t == centerTile) continue;
			if (placedIds.contains(t.getId())) continue;
			if (!prerequisitesSatisfied(t, satisfiedIds)) continue;
			available.add(t);
		}

		// 6. Neighbor areas (from areas.json): area ids that are neighbors of any unlocked area and not yet unlocked
		Set<String> neighborAreaIds = getNeighborAreaIdsOfUnlocked();

		// 7. Partition: skill 1-10 (band 0) for rings 1–2; rest for ring 3+
		List<WorldUnlockTile> skill1To10 = new ArrayList<>();
		List<WorldUnlockTile> rest = new ArrayList<>();
		for (WorldUnlockTile t : available)
		{
			if (getSkillLevelBand(t) == 0)
				skill1To10.add(t);
			else
				rest.add(t);
		}

		int seed = getGridSeed();
		Random rng = new Random(seed);
		// Ring 1 and 2: skills 1-10 only — put neighbor-area tiles last so they are not used for ring 1–2
		skill1To10 = orderNeighborAreasLast(skill1To10, neighborAreaIds, rng);
		// Ring 3+: neighbor areas first, then others
		rest = orderNeighborAreasFirst(rest, neighborAreaIds, rng);

		// 8. Sort toAssign by spiral order (ring 1 first, then ring 2, then 3+)
		Comparator<int[]> byDistFromCenter = (a, b) -> {
			int da = chebyshevDist(a[0], a[1], 0, 0);
			int db = chebyshevDist(b[0], b[1], 0, 0);
			if (da != db) return Integer.compare(da, db);
			if (a[0] != b[0]) return Integer.compare(a[0], b[0]);
			return Integer.compare(a[1], b[1]);
		};
		List<int[]> toAssignRc = new ArrayList<>();
		for (String pos : toAssign)
		{
			int[] rc = parsePos(pos);
			if (rc != null) toAssignRc.add(rc);
		}
		toAssignRc.sort(byDistFromCenter);

		// 9. Assign only to toAssign: ring 1 (8) and ring 2 (16) get skill 1-10 first; ring 3+ get rest. One-use.
		int skill1To10Idx = 0;
		int restIdx = 0;
		for (int[] rc : toAssignRc)
		{
			String pos = rc[0] + "," + rc[1];
			int ring = chebyshevDist(rc[0], rc[1], 0, 0);
			WorldUnlockTile chosen = null;
			if (ring == 1 && skill1To10Idx < skill1To10.size())
			{
				chosen = skill1To10.get(skill1To10Idx++);
			}
			else if (ring == 2)
			{
				if (skill1To10Idx < skill1To10.size())
					chosen = skill1To10.get(skill1To10Idx++);
				else if (restIdx < rest.size())
					chosen = rest.get(restIdx++);
			}
			else
			{
				if (restIdx < rest.size())
					chosen = rest.get(restIdx++);
			}
			if (chosen != null)
			{
				gridState.put(pos, chosen.getId());
				placedIds.add(chosen.getId());
				satisfiedIds.add(chosen.getId());
			}
		}

		saveGridState(gridState);

		// 10. Output: center + all placements from grid state (sorted by position)
		List<WorldUnlockTilePlacement> grid = new ArrayList<>();
		grid.add(new WorldUnlockTilePlacement(centerTile, 0, 0));
		List<String> positionsSorted = new ArrayList<>(gridState.keySet());
		positionsSorted.sort((a, b) -> {
			int[] ar = parsePos(a), br = parsePos(b);
			if (ar == null || br == null) return 0;
			return byDistFromCenter.compare(ar, br);
		});
		for (String posStr : positionsSorted)
		{
			int[] rc = parsePos(posStr);
			if (rc == null) continue;
			String tileId = gridState.get(posStr);
			WorldUnlockTile tile = getTileById(tileId);
			if (tile != null)
				grid.add(new WorldUnlockTilePlacement(tile, rc[0], rc[1]));
		}
		return grid;
	}

	/** Area ids that are neighbors (in areas.json) of any unlocked area tile and not yet unlocked. */
	private Set<String> getNeighborAreaIdsOfUnlocked()
	{
		Set<String> neighborIds = new HashSet<>();
		for (String tileId : unlockedIds)
		{
			WorldUnlockTile tile = getTileById(tileId);
			if (tile == null || !"area".equals(tile.getType()))
				continue;
			Area area = areaGraphService.getArea(tileId);
			if (area == null || area.getNeighbors() == null)
				continue;
			for (String n : area.getNeighbors())
			{
				if (n != null && !n.isEmpty() && !unlockedIds.contains(n))
					neighborIds.add(n);
			}
		}
		return neighborIds;
	}

	/** Puts tiles that are area type and whose id is in neighborAreaIds at the front, then shuffles each part. */
	private static List<WorldUnlockTile> orderNeighborAreasFirst(List<WorldUnlockTile> list, Set<String> neighborAreaIds, Random rng)
	{
		List<WorldUnlockTile> neighbor = new ArrayList<>();
		List<WorldUnlockTile> other = new ArrayList<>();
		for (WorldUnlockTile t : list)
		{
			if ("area".equals(t.getType()) && neighborAreaIds.contains(t.getId()))
				neighbor.add(t);
			else
				other.add(t);
		}
		Collections.shuffle(neighbor, rng);
		Collections.shuffle(other, rng);
		List<WorldUnlockTile> out = new ArrayList<>(neighbor);
		out.addAll(other);
		return out;
	}

	/** Puts tiles that are area type and whose id is in neighborAreaIds at the end, then shuffles each part. Used for ring 1–2 so skills 1–10 fill first. */
	private static List<WorldUnlockTile> orderNeighborAreasLast(List<WorldUnlockTile> list, Set<String> neighborAreaIds, Random rng)
	{
		List<WorldUnlockTile> other = new ArrayList<>();
		List<WorldUnlockTile> neighbor = new ArrayList<>();
		for (WorldUnlockTile t : list)
		{
			if ("area".equals(t.getType()) && neighborAreaIds.contains(t.getId()))
				neighbor.add(t);
			else
				other.add(t);
		}
		Collections.shuffle(other, rng);
		Collections.shuffle(neighbor, rng);
		List<WorldUnlockTile> out = new ArrayList<>(other);
		out.addAll(neighbor);
		return out;
	}

	private static boolean prerequisitesSatisfied(WorldUnlockTile tile, Set<String> satisfiedIds)
	{
		if (tile.getPrerequisites() == null || tile.getPrerequisites().isEmpty())
			return true;
		return tile.getPrerequisites().stream().allMatch(satisfiedIds::contains);
	}

	private static int chebyshevDist(int r1, int c1, int r2, int c2)
	{
		return Math.max(Math.abs(r1 - r2), Math.abs(c1 - c2));
	}

	/** Level band index for skill tiles: 0 = 1-10, 1 = 11-20, ..., 9 = 91-99. Returns -1 if not a skill tile. */
	private static int getSkillLevelBand(WorldUnlockTile t)
	{
		if (!"skill".equals(t.getType())) return -1;
		TaskLink link = t.getTaskLink();
		if (link == null) return -1;
		Integer levelMax = link.getLevelMax();
		if (levelMax == null) return -1;
		return (levelMax - 1) / 10;
	}

	/** True if tile is a skill unlock, tier 1, with level band 1-10 (levelMax <= 10). */
	private static boolean isSkillLevel1To10(WorldUnlockTile t)
	{
		return getSkillLevelBand(t) == 0 && t.getTier() == 1;
	}

	/** Spiral order for one ring (same as TaskGridService). Ring 1 = 8 cells, ring 2 = 16, etc. */
	private static List<int[]> spiralOrderForRing(int tier)
	{
		List<int[]> out = new ArrayList<>(8 * tier);
		for (int c = 1 - tier; c <= tier; c++) out.add(new int[]{ tier, c });
		for (int r = tier - 1; r >= -tier; r--) out.add(new int[]{ r, tier });
		for (int c = tier - 1; c >= -tier; c--) out.add(new int[]{ -tier, c });
		for (int r = -tier + 1; r <= tier; r++) out.add(new int[]{ r, -tier });
		return out;
	}

	/**
	 * True if this tile is revealed. Same logic as Task Grid: center (0,0) is always revealed;
	 * any other tile is revealed only when at least one cardinal neighbor (up/down/left/right) is unlocked.
	 */
	public boolean isRevealed(WorldUnlockTilePlacement placement, Set<String> unlocked, List<WorldUnlockTilePlacement> grid)
	{
		int row = placement.getRow(), col = placement.getCol();
		if (row == 0 && col == 0)
			return true; // center (starter) is always revealed

		// Cardinal neighbors only: (r±1,c) and (r,c±1) — same as TaskGridService.isRevealed
		int[][] deltas = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		java.util.Map<String, String> posToId = new java.util.HashMap<>();
		for (WorldUnlockTilePlacement p : grid)
			posToId.put(p.getRow() + "," + p.getCol(), p.getTile().getId());

		for (int[] d : deltas)
		{
			int nr = row + d[0], nc = col + d[1];
			String neighborId = posToId.get(nr + "," + nc);
			if (neighborId != null && unlocked.contains(neighborId))
				return true;
		}
		return false;
	}

	/** Returns all unlock tiles from world_unlocks.json (after load()). */
	public List<WorldUnlockTile> getTiles()
	{
		if (!loaded)
		{
			load();
		}
		return Collections.unmodifiableList(tiles);
	}

	/** Returns the set of unlocked tile ids. */
	public Set<String> getUnlockedIds()
	{
		if (!loaded)
		{
			load();
		}
		return Collections.unmodifiableSet(new HashSet<>(unlockedIds));
	}

	/** Returns the tile with the given id, or null. */
	public WorldUnlockTile getTileById(String id)
	{
		if (!loaded)
		{
			load();
		}
		return tiles.stream().filter(t -> id.equals(t.getId())).findFirst().orElse(null);
	}

	/**
	 * Unlocks the tile with the given id if prerequisites are met and cost can be spent.
	 * Returns true on success.
	 */
	public boolean unlock(String tileId, int cost)
	{
		if (!loaded)
		{
			load();
		}
		WorldUnlockTile tile = getTileById(tileId);
		if (tile == null)
		{
			return false;
		}
		if (unlockedIds.contains(tileId))
		{
			return true; // already unlocked
		}
		if (tile.getPrerequisites() != null)
		{
			for (String prereq : tile.getPrerequisites())
			{
				if (!unlockedIds.contains(prereq))
				{
					return false;
				}
			}
		}
		if (cost > 0 && !pointsService.spend(cost))
		{
			return false;
		}
		unlockedIds.add(tileId);
		persistUnlocked();
		return true;
	}

	/** Returns true if all prerequisites of the tile are unlocked. */
	public boolean isUnlockable(WorldUnlockTile tile)
	{
		if (tile.getPrerequisites() == null || tile.getPrerequisites().isEmpty())
		{
			return true;
		}
		return tile.getPrerequisites().stream().allMatch(unlockedIds::contains);
	}

	/** Returns tiles that are not yet unlocked and whose prerequisites are satisfied. */
	public List<WorldUnlockTile> getUnlockableTiles()
	{
		if (!loaded)
		{
			load();
		}
		return tiles.stream()
			.filter(t -> !unlockedIds.contains(t.getId()))
			.filter(this::isUnlockable)
			.collect(Collectors.toList());
	}

	/** Clears all unlocked world-unlock ids and grid state (e.g. on reset). Next getGrid() will re-roll from center. */
	public void clearUnlocked()
	{
		unlockedIds.clear();
		persistUnlocked();
		configManager.unsetConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_GRID_STATE);
	}

	/**
	 * Returns the list of task definitions associated with this unlock tile (resolved from taskLink against tasks.json).
	 */
	public List<TaskDefinition> getTasksForUnlock(String tileId)
	{
		WorldUnlockTile tile = getTileById(tileId);
		if (tile == null || tile.getTaskLink() == null)
		{
			return Collections.emptyList();
		}
		List<TaskDefinition> all = taskGridService.getEffectiveDefaultTasks();
		TaskLink link = tile.getTaskLink();
		String linkType = link.getType() != null ? link.getType() : "";

		switch (linkType)
		{
			case "area":
				// Tasks where task.area/areas contains this tile's id (area tile id = area id)
				String areaId = tile.getId();
				return all.stream()
					.filter(t -> t.getRequiredAreaIds().contains(areaId))
					.collect(Collectors.toList());
			case "skill":
				// taskType matches skillName, difficulty matches tier from level band (1-39->1, 40-59->2, 60-79->3, 80-89->4, 90-99->5)
				String skillName = link.getSkillName();
				int tier = levelBandToTier(link.getLevelMin() != null ? link.getLevelMin() : 1);
				return all.stream()
					.filter(t -> (skillName == null || skillName.equalsIgnoreCase(t.getTaskType())) && t.getDifficulty() == tier)
					.collect(Collectors.toList());
			case "taskFilter":
				return all.stream()
					.filter(t -> matchTaskFilter(t, link))
					.collect(Collectors.toList());
			case "taskDisplayNames":
				if (link.getTaskDisplayNames() == null || link.getTaskDisplayNames().isEmpty())
					return Collections.emptyList();
				java.util.Set<String> names = link.getTaskDisplayNames().stream()
					.map(s -> s != null ? s.trim().toLowerCase() : "")
					.collect(Collectors.toSet());
				return all.stream()
					.filter(t -> t.getDisplayName() != null && names.contains(t.getDisplayName().trim().toLowerCase()))
					.collect(Collectors.toList());
			default:
				return Collections.emptyList();
		}
	}

	private static int levelBandToTier(int level)
	{
		if (level <= 39) return 1;
		if (level <= 59) return 2;
		if (level <= 79) return 3;
		if (level <= 89) return 4;
		return 5;
	}

	private static boolean matchTaskFilter(TaskDefinition t, TaskLink link)
	{
		if (link.getTaskType() != null && !link.getTaskType().equalsIgnoreCase(t.getTaskType()))
			return false;
		if (link.getRequirementsContains() != null && !link.getRequirementsContains().isEmpty())
		{
			String req = t.getRequirements();
			if (req == null || !req.toLowerCase().contains(link.getRequirementsContains().toLowerCase()))
				return false;
		}
		if (link.getDifficulty() != null && link.getDifficulty() != t.getDifficulty())
			return false;
		return true;
	}
}
