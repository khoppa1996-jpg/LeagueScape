package com.leaguescape.worldunlock;

import com.google.gson.Gson;
import com.leaguescape.points.PointsService;
import com.leaguescape.task.TaskDefinition;
import com.leaguescape.task.TaskGridService;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
	private static final String WORLD_UNLOCKS_RESOURCE = "/world_unlocks.json";

	private final ConfigManager configManager;
	private final PointsService pointsService;
	private final TaskGridService taskGridService;

	private List<WorldUnlockTile> tiles = new ArrayList<>();
	private final Set<String> unlockedIds = new HashSet<>();
	private boolean loaded = false;

	@Inject
	public WorldUnlockService(ConfigManager configManager, PointsService pointsService, TaskGridService taskGridService)
	{
		this.configManager = configManager;
		this.pointsService = pointsService;
		this.taskGridService = taskGridService;
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

	/** Returns the grid seed used for layout; increment on reset to reshuffle. */
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
	 * Returns the world unlock grid: center (0,0) = start tile (no prereqs, cost 0), then tiles
	 * distributed by tier in spiral rings (tier 1 near center, higher tiers outward). Within each
	 * tier, tiles are shuffled for type diversity. All tiles are included.
	 */
	public List<WorldUnlockTilePlacement> getGrid()
	{
		if (!loaded) load();
		List<WorldUnlockTile> all = new ArrayList<>(tiles);
		if (all.isEmpty()) return Collections.emptyList();

		WorldUnlockTile centerTile = null;
		List<WorldUnlockTile> rest = new ArrayList<>();
		for (WorldUnlockTile t : all)
		{
			if ((t.getPrerequisites() == null || t.getPrerequisites().isEmpty()) && t.getCost() == 0)
			{
				if (centerTile == null) centerTile = t;
				else rest.add(t);
			}
			else rest.add(t);
		}
		if (centerTile == null)
		{
			centerTile = all.get(0);
			rest = all.size() > 1 ? new ArrayList<>(all.subList(1, all.size())) : new ArrayList<>();
		}

		int seed = getGridSeed();
		Random rng = new Random(seed);

		int maxTier = 5;
		int ring1Slots = 8;
		int ring2Slots = 16;
		int earlySlots = ring1Slots + ring2Slots; // 24

		// Early skill tiles: type=skill, tier=1, level band 1-10 (levelMax <= 10)
		List<WorldUnlockTile> earlySkills = new ArrayList<>();
		List<WorldUnlockTile> restByTier = new ArrayList<>();
		for (WorldUnlockTile t : rest)
		{
			if (isSkillLevel1To10(t))
				earlySkills.add(t);
			else
				restByTier.add(t);
		}
		Collections.shuffle(earlySkills, rng);

		// Group rest by tier
		List<List<WorldUnlockTile>> byTier = new ArrayList<>();
		for (int t = 0; t <= maxTier; t++)
			byTier.add(new ArrayList<>());
		for (WorldUnlockTile t : restByTier)
		{
			int tier = Math.max(1, Math.min(maxTier, t.getTier()));
			byTier.get(tier).add(t);
		}
		for (int t = 1; t <= maxTier; t++)
			Collections.shuffle(byTier.get(t), rng);

		// Build ordered list: first 24 slots = early skills (1-10), then rest by tier
		List<WorldUnlockTile> restOrdered = new ArrayList<>();
		for (int t = 1; t <= maxTier; t++)
			restOrdered.addAll(byTier.get(t));

		List<WorldUnlockTile> ordered = new ArrayList<>();
		for (int i = 0; i < Math.min(earlySlots, earlySkills.size()); i++)
			ordered.add(earlySkills.get(i));
		int needFill = earlySlots - ordered.size();
		for (int i = 0; i < needFill && i < restOrdered.size(); i++)
			ordered.add(restOrdered.get(i));
		for (int i = needFill; i < restOrdered.size(); i++)
			ordered.add(restOrdered.get(i));

		List<int[]> positions = new ArrayList<>();
		positions.add(new int[]{ 0, 0 });
		int ring = 1;
		while (positions.size() < 1 + ordered.size())
		{
			positions.addAll(spiralOrderForRing(ring));
			ring++;
		}

		List<WorldUnlockTilePlacement> grid = new ArrayList<>();
		grid.add(new WorldUnlockTilePlacement(centerTile, 0, 0));
		for (int i = 0; i < ordered.size() && i + 1 < positions.size(); i++)
		{
			int[] rc = positions.get(i + 1);
			grid.add(new WorldUnlockTilePlacement(ordered.get(i), rc[0], rc[1]));
		}
		return grid;
	}

	/** True if tile is a skill unlock, tier 1, with level band 1-10 (levelMax <= 10). */
	private static boolean isSkillLevel1To10(WorldUnlockTile t)
	{
		if (!"skill".equals(t.getType()) || t.getTier() != 1)
			return false;
		TaskLink link = t.getTaskLink();
		if (link == null) return false;
		Integer levelMax = link.getLevelMax();
		Integer levelMin = link.getLevelMin();
		return levelMax != null && levelMax <= 10 && (levelMin == null || levelMin <= 10);
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

	/** Clears all unlocked world-unlock ids (e.g. on reset). */
	public void clearUnlocked()
	{
		unlockedIds.clear();
		persistUnlocked();
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
