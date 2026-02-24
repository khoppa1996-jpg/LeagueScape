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
	 * Returns the world unlock grid: center (0,0) = start tile (no prereqs, cost 0).
	 * Ring 1 (8 slots) and ring 2 (16 slots) are skill unlocks only (level 1-10, then 11-20 to fill).
	 * From ring 3 onward, skills are always prioritized; areas, quests, achievement diaries, and
	 * bosses are added slowly (interleaved so skills dominate).
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

		int ring1Slots = 8;
		int ring2Slots = 16;

		// Partition: skills by level band (0=1-10, 1=11-20, ...), and non-skills by type
		List<List<WorldUnlockTile>> skillsByBand = new ArrayList<>();
		for (int b = 0; b <= 9; b++)
			skillsByBand.add(new ArrayList<>());
		List<WorldUnlockTile> areas = new ArrayList<>();
		List<WorldUnlockTile> quests = new ArrayList<>();
		List<WorldUnlockTile> diaries = new ArrayList<>();
		List<WorldUnlockTile> bosses = new ArrayList<>();
		List<WorldUnlockTile> other = new ArrayList<>();

		for (WorldUnlockTile t : rest)
		{
			int band = getSkillLevelBand(t);
			if (band >= 0)
			{
				skillsByBand.get(band).add(t);
			}
			else
			{
				String type = t.getType();
				if ("area".equals(type)) areas.add(t);
				else if ("quest".equals(type)) quests.add(t);
				else if ("achievement_diary".equals(type)) diaries.add(t);
				else if ("boss".equals(type)) bosses.add(t);
				else other.add(t);
			}
		}

		for (List<WorldUnlockTile> list : skillsByBand)
			Collections.shuffle(list, rng);
		Collections.shuffle(areas, rng);
		Collections.shuffle(quests, rng);
		Collections.shuffle(diaries, rng);
		Collections.shuffle(bosses, rng);
		Collections.shuffle(other, rng);

		// Ring 1 (8): skill 1-10 only
		List<WorldUnlockTile> ring1 = new ArrayList<>();
		List<WorldUnlockTile> band0 = skillsByBand.get(0);
		for (int i = 0; i < Math.min(ring1Slots, band0.size()); i++)
			ring1.add(band0.get(i));
		int ring1Used = ring1.size();

		// Ring 2 (16): remaining skill 1-10, then skill 11-20 to fill
		List<WorldUnlockTile> ring2 = new ArrayList<>();
		for (int i = ring1Used; i < band0.size() && ring2.size() < ring2Slots; i++)
			ring2.add(band0.get(i));
		for (int b = 1; b <= 9 && ring2.size() < ring2Slots; b++)
		{
			for (WorldUnlockTile t : skillsByBand.get(b))
			{
				if (ring2.size() >= ring2Slots) break;
				ring2.add(t);
			}
		}

		// Remaining skills (all bands): those not used in ring 1 or 2
		int fromBand0InRing2 = Math.min(band0.size() - ring1Used, ring2Slots);
		int fromBand1InRing2 = Math.min(skillsByBand.get(1).size(), Math.max(0, ring2Slots - (band0.size() - ring1Used)));
		List<WorldUnlockTile> remainingSkills = new ArrayList<>();
		for (int i = ring1Used + fromBand0InRing2; i < band0.size(); i++)
			remainingSkills.add(band0.get(i));
		for (int b = 1; b <= 9; b++)
		{
			List<WorldUnlockTile> band = skillsByBand.get(b);
			int usedInRing2 = (b == 1) ? fromBand1InRing2 : 0;
			for (int i = usedInRing2; i < band.size(); i++)
				remainingSkills.add(band.get(i));
		}
		remainingSkills.sort((a, b) -> {
			int ta = a.getTier(), tb = b.getTier();
			if (ta != tb) return Integer.compare(ta, tb);
			return Integer.compare(getSkillLevelBand(a), getSkillLevelBand(b));
		});

		// Non-skills: round-robin for slow interleave (area, quest, diary, boss, ...)
		List<WorldUnlockTile> nonSkillsRoundRobin = new ArrayList<>();
		int maxNon = Math.max(Math.max(areas.size(), quests.size()), Math.max(diaries.size(), bosses.size()));
		for (int i = 0; i < maxNon; i++)
		{
			if (i < areas.size()) nonSkillsRoundRobin.add(areas.get(i));
			if (i < quests.size()) nonSkillsRoundRobin.add(quests.get(i));
			if (i < diaries.size()) nonSkillsRoundRobin.add(diaries.get(i));
			if (i < bosses.size()) nonSkillsRoundRobin.add(bosses.get(i));
		}
		nonSkillsRoundRobin.addAll(other);

		// Ring 3+: prioritize skills; every SKILLS_PER_NON_SKILL skills add one non-skill
		final int SKILLS_PER_NON_SKILL = 6;
		List<WorldUnlockTile> restOrdered = new ArrayList<>();
		int skillIdx = 0;
		int nonIdx = 0;
		while (skillIdx < remainingSkills.size() || nonIdx < nonSkillsRoundRobin.size())
		{
			for (int k = 0; k < SKILLS_PER_NON_SKILL && skillIdx < remainingSkills.size(); k++)
			{
				restOrdered.add(remainingSkills.get(skillIdx++));
			}
			if (nonIdx < nonSkillsRoundRobin.size())
			{
				restOrdered.add(nonSkillsRoundRobin.get(nonIdx++));
			}
		}
		while (skillIdx < remainingSkills.size())
			restOrdered.add(remainingSkills.get(skillIdx++));

		List<WorldUnlockTile> ordered = new ArrayList<>();
		ordered.addAll(ring1);
		ordered.addAll(ring2);
		ordered.addAll(restOrdered);

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
