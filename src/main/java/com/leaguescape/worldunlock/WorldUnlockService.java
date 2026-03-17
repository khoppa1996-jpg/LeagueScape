package com.leaguescape.worldunlock;

import com.google.gson.Gson;
import com.leaguescape.LeagueScapeConfig;
import com.leaguescape.area.AreaGraphService;
import com.leaguescape.constants.WorldUnlockTileType;
import com.leaguescape.util.ConfigParsing;
import com.leaguescape.util.LeagueScapeConfigConstants;
import com.leaguescape.util.ResourceJsonLoader;
import com.leaguescape.util.ResourcePaths;
import com.leaguescape.data.Area;
import com.leaguescape.points.PointsService;
import com.leaguescape.task.TaskDefinition;
import com.leaguescape.task.TaskGridService;
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
	private static final String STATE_GROUP = LeagueScapeConfigConstants.STATE_GROUP;
	private static final String KEY_WORLD_UNLOCK_UNLOCKED_IDS = "worldUnlockUnlockedIds";
	private static final String KEY_WORLD_UNLOCK_CLAIMED_IDS = "worldUnlockClaimedIds";
	private static final String KEY_WORLD_UNLOCK_GRID_SEED = "worldUnlockGridSeed";
	private static final String KEY_WORLD_UNLOCK_GRID_STATE = "worldUnlockGridState";
	/** Grid state entry format: pos##tileId (same idea as Global Task grid). */
	private static final String GRID_STATE_SEP = "##";
	private static final String POS_ENTRY_SEP = "||";

	private final ConfigManager configManager;
	private final LeagueScapeConfig config;
	private final PointsService pointsService;
	private final TaskGridService taskGridService;
	private final AreaGraphService areaGraphService;

	private List<WorldUnlockTile> tiles = new ArrayList<>();
	private final Set<String> unlockedIds = new HashSet<>();
	private final Set<String> claimedIds = new HashSet<>();
	private boolean loaded = false;
	/** Lazy-built: area id -> achievement diary key (e.g. varrock -> varrock, al_kharid -> desert). */
	private Map<String, String> areaIdToDiaryKey = null;

	@Inject
	public WorldUnlockService(ConfigManager configManager, LeagueScapeConfig config, PointsService pointsService,
		TaskGridService taskGridService, AreaGraphService areaGraphService)
	{
		this.configManager = configManager;
		this.config = config;
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
		WorldUnlocksData data = ResourceJsonLoader.load(getClass(), ResourcePaths.WORLD_UNLOCKS_JSON, WorldUnlocksData.class, gson, log);
		if (data != null && data.getUnlocks() != null)
		{
			tiles = data.getUnlocks();
		}
		unlockedIds.addAll(ConfigParsing.parseCommaSeparatedSet(configManager.getConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_UNLOCKED_IDS)));
		claimedIds.clear();
		claimedIds.addAll(ConfigParsing.parseCommaSeparatedSet(configManager.getConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_CLAIMED_IDS)));
		loaded = true;
	}

	private void persistUnlocked()
	{
		configManager.setConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_UNLOCKED_IDS, ConfigParsing.joinComma(unlockedIds));
	}

	private void persistClaimed()
	{
		configManager.setConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_CLAIMED_IDS, ConfigParsing.joinComma(claimedIds));
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
	 * Skill level-up tiles are prioritized: until all skill tiles are unlocked, each new slot has a 75% chance
	 * to be filled by a skill tile and 25% by a non-skill tile. When filling a non-skill slot, progression order is:
	 * quests first, then bosses, then areas, then other (e.g. achievement diary). Once all skills are unlocked, non-skill tiles in that order are preferred.
	 */
	public List<WorldUnlockTilePlacement> getGrid()
	{
		if (!loaded) load();
		List<WorldUnlockTile> all = new ArrayList<>(tiles);
		if (all.isEmpty()) return Collections.emptyList();

		// Center = configured starter area tile if present, else first tile with no prereqs and cost 0, else first in list
		WorldUnlockTile centerTile = null;
		String startArea = config.startingArea();
		if (startArea != null && !startArea.isEmpty())
		{
			centerTile = getTileById(startArea);
		}
		if (centerTile == null)
		{
			for (WorldUnlockTile t : all)
			{
				if ((t.getPrerequisites() == null || t.getPrerequisites().isEmpty()) && getTileCost(t) == 0)
				{
					centerTile = t;
					break;
				}
			}
		}
		if (centerTile == null)
			centerTile = all.get(0);

		// 1. Single grid state: position -> tile id. Only add when first revealed; never overwrite.
		Map<String, String> gridState = new HashMap<>(loadGridState());

		// 2. Claimed positions = positions whose tile has been claimed (unlock + action done). Only these reveal neighbors.
		Set<String> claimedPositions = new HashSet<>();
		claimedPositions.add("0,0"); // center counts as claimed for reveal so its neighbors are always revealed
		for (Map.Entry<String, String> e : gridState.entrySet())
		{
			if (claimedIds.contains(e.getValue()))
				claimedPositions.add(e.getKey());
		}

		// 3. Revealed = center + claimed positions + neighbors of claimed positions (all sides of each claimed tile)
		Set<String> revealedPositions = new HashSet<>();
		revealedPositions.add("0,0");
		for (String cp : claimedPositions)
		{
			revealedPositions.add(normalizePos(cp));
			for (String neighbor : getNeighborPositions(cp))
				revealedPositions.add(normalizePos(neighbor));
		}

		// 4. toAssign = revealed positions that have no assignment yet (first time revealed), deduplicated
		Set<String> placedIds = new HashSet<>(gridState.values());
		Set<String> toAssignSet = new HashSet<>();
		for (String pos : revealedPositions)
		{
			String norm = normalizePos(pos);
			if ("0,0".equals(norm)) continue;
			if (!gridState.containsKey(norm))
				toAssignSet.add(norm);
		}
		List<String> toAssign = new ArrayList<>(toAssignSet);

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

		// 7. Partition available into skill, quest, boss, and area (and other). Progression: quests first, then bosses, then areas when filling non-skill slots.
		List<WorldUnlockTile> skillTiles = new ArrayList<>();
		List<WorldUnlockTile> questTiles = new ArrayList<>();
		List<WorldUnlockTile> bossTiles = new ArrayList<>();
		List<WorldUnlockTile> areaTiles = new ArrayList<>();
		List<WorldUnlockTile> otherNonSkillTiles = new ArrayList<>();
		for (WorldUnlockTile t : available)
		{
			if (WorldUnlockTileType.SKILL.equals(t.getType()))
				skillTiles.add(t);
			else if (WorldUnlockTileType.QUEST.equals(t.getType()))
				questTiles.add(t);
			else if (WorldUnlockTileType.BOSS.equals(t.getType()))
				bossTiles.add(t);
			else if (WorldUnlockTileType.AREA.equals(t.getType()))
				areaTiles.add(t);
			else
				otherNonSkillTiles.add(t);
		}
		int seed = getGridSeed();
		Random rng = new Random(seed);
		// Order areas: neighbor areas first, then others
		areaTiles = orderNeighborAreasFirst(areaTiles, neighborAreaIds, rng);
		Collections.shuffle(skillTiles, rng);
		Collections.shuffle(questTiles, rng);
		Collections.shuffle(bossTiles, rng);

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

		// 9. Assign: rings 1–2 = only skill unlocks level 1–10; ring 3+ = 70% skill, 10% quest (if starting area unlocked), 10% area (if neighbor), 5% boss (if area unlocked), 5% diary (if area unlocked).
		for (int[] rc : toAssignRc)
		{
			String pos = rc[0] + "," + rc[1];
			int ring = chebyshevDist(rc[0], rc[1], 0, 0);
			WorldUnlockTile chosen = null;

			if (ring <= 2)
			{
				// First two rings: only skill unlocks level 1–10
				List<WorldUnlockTile> skill1To10 = skillTiles.stream().filter(WorldUnlockService::isSkillLevel1To10).collect(Collectors.toList());
				if (!skill1To10.isEmpty())
				{
					chosen = skill1To10.get(rng.nextInt(skill1To10.size()));
					skillTiles.remove(chosen);
				}
			}
			else
			{
				// Ring 3+: weighted roll with eligibility
				double roll = rng.nextDouble();
				List<WorldUnlockTile> questEligible = questTiles.stream().filter(this::hasUnlockedStartingArea).collect(Collectors.toList());
				List<WorldUnlockTile> areaEligible = areaTiles.stream().filter(t -> neighborAreaIds.contains(t.getId())).collect(Collectors.toList());
				List<WorldUnlockTile> bossEligible = bossTiles.stream().filter(this::hasUnlockedStartingArea).collect(Collectors.toList());
				List<WorldUnlockTile> diaryEligible = otherNonSkillTiles.stream()
					.filter(t -> WorldUnlockTileType.ACHIEVEMENT_DIARY.equals(t.getType()) && hasUnlockedStartingArea(t))
					.collect(Collectors.toList());

				if (roll < 0.70 && !skillTiles.isEmpty())
					chosen = skillTiles.remove(rng.nextInt(skillTiles.size()));
				else if (roll < 0.80 && !questEligible.isEmpty())
					chosen = removeRandom(questTiles, questEligible.get(rng.nextInt(questEligible.size())), rng);
				else if (roll < 0.90 && !areaEligible.isEmpty())
					chosen = removeRandom(areaTiles, areaEligible.get(rng.nextInt(areaEligible.size())), rng);
				else if (roll < 0.95 && !bossEligible.isEmpty())
					chosen = removeRandom(bossTiles, bossEligible.get(rng.nextInt(bossEligible.size())), rng);
				else if (!diaryEligible.isEmpty())
					chosen = removeRandom(otherNonSkillTiles, diaryEligible.get(rng.nextInt(diaryEligible.size())), rng);

				// Fallback if rolled category was empty: skill → quest → area → boss → diary
				if (chosen == null && !skillTiles.isEmpty())
					chosen = skillTiles.remove(rng.nextInt(skillTiles.size()));
				if (chosen == null && !questEligible.isEmpty())
					chosen = removeRandom(questTiles, questEligible.get(rng.nextInt(questEligible.size())), rng);
				if (chosen == null && !areaEligible.isEmpty())
					chosen = removeRandom(areaTiles, areaEligible.get(rng.nextInt(areaEligible.size())), rng);
				if (chosen == null && !bossEligible.isEmpty())
					chosen = removeRandom(bossTiles, bossEligible.get(rng.nextInt(bossEligible.size())), rng);
				if (chosen == null && !diaryEligible.isEmpty())
					chosen = removeRandom(otherNonSkillTiles, diaryEligible.get(rng.nextInt(diaryEligible.size())), rng);
			}

			// Final fallback: always assign a tile to every revealed position so no empty gaps appear
			if (chosen == null)
			{
				List<WorldUnlockTile> stillAvailable = new ArrayList<>();
				for (WorldUnlockTile t : all)
				{
					if (t == centerTile) continue;
					if (placedIds.contains(t.getId())) continue;
					if (!prerequisitesSatisfied(t, satisfiedIds)) continue;
					stillAvailable.add(t);
				}
				if (!stillAvailable.isEmpty())
				{
					chosen = stillAvailable.get(rng.nextInt(stillAvailable.size()));
					skillTiles.remove(chosen);
					questTiles.remove(chosen);
					areaTiles.remove(chosen);
					bossTiles.remove(chosen);
					otherNonSkillTiles.remove(chosen);
				}
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
			if (tile == null || !WorldUnlockTileType.AREA.equals(tile.getType()))
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
			if (WorldUnlockTileType.AREA.equals(t.getType()) && neighborAreaIds.contains(t.getId()))
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

	/** Removes the given tile from the list and returns it. */
	private static WorldUnlockTile removeRandom(List<WorldUnlockTile> list, WorldUnlockTile tile, Random rng)
	{
		int i = list.indexOf(tile);
		if (i < 0) return null;
		return list.remove(i);
	}

	/** Puts tiles that are area type and whose id is in neighborAreaIds at the end, then shuffles each part. Used for ring 1–2 so skills 1–10 fill first. */
	private static List<WorldUnlockTile> orderNeighborAreasLast(List<WorldUnlockTile> list, Set<String> neighborAreaIds, Random rng)
	{
		List<WorldUnlockTile> other = new ArrayList<>();
		List<WorldUnlockTile> neighbor = new ArrayList<>();
		for (WorldUnlockTile t : list)
		{
			if (WorldUnlockTileType.AREA.equals(t.getType()) && neighborAreaIds.contains(t.getId()))
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

	/**
	 * Returns the skill tile id whose level band contains the given level (e.g. 50 and "Agility" -> "agility_41_50").
	 * Used so task requirements like "50 Agility" only populate when that skill bracket is unlocked.
	 */
	public String getSkillTileIdForLevel(String skillName, int level)
	{
		if (skillName == null || (skillName = skillName.trim()).isEmpty()) return null;
		if (!loaded) load();
		for (WorldUnlockTile t : tiles)
		{
			if (!WorldUnlockTileType.SKILL.equals(t.getType())) continue;
			TaskLink link = t.getTaskLink();
			if (link == null || !skillName.equalsIgnoreCase(link.getSkillName())) continue;
			Integer min = link.getLevelMin();
			Integer max = link.getLevelMax();
			if (min != null && max != null && level >= min && level <= max)
				return t.getId();
		}
		return null;
	}

	/**
	 * Resolves a prerequisite or requirement string to a World Unlock tile id.
	 * If the string matches (case-insensitive) any tile's id or displayName, returns that tile's id.
	 * Used so prerequisites/requirements can reference areas, quests, or bosses by id or display name.
	 */
	public String resolvePrerequisiteToTileId(String prereq)
	{
		if (prereq == null || (prereq = prereq.trim()).isEmpty()) return null;
		if (!loaded) load();
		for (WorldUnlockTile t : tiles)
		{
			if (prereq.equalsIgnoreCase(t.getId())) return t.getId();
			if (t.getDisplayName() != null && prereq.equalsIgnoreCase(t.getDisplayName().trim())) return t.getId();
		}
		return null;
	}

	/**
	 * True only when all prerequisites are satisfied (AND logic).
	 * A prerequisite is satisfied if it is resolved to a tile id (by id or displayName match) and that tile is in satisfiedIds (unlocked or already placed in the grid).
	 * Prerequisites that do not resolve to any tile (e.g. skill-only strings) are not enforced here; they are not in world_unlocks.
	 */
	private boolean prerequisitesSatisfied(WorldUnlockTile tile, Set<String> satisfiedIds)
	{
		if (tile.getPrerequisites() == null || tile.getPrerequisites().isEmpty())
			return true;
		return tile.getPrerequisites().stream().allMatch(prereq -> {
			String tileId = resolvePrerequisiteToTileId(prereq);
			String toCheck = (tileId != null) ? tileId : prereq.trim();
			return satisfiedIds.contains(toCheck);
		});
	}

	private static int chebyshevDist(int r1, int c1, int r2, int c2)
	{
		return Math.max(Math.abs(r1 - r2), Math.abs(c1 - c2));
	}

	/** Level band index for skill tiles: 0 = 1-10, 1 = 11-20, ..., 9 = 91-99. Returns -1 if not a skill tile. */
	private static int getSkillLevelBand(WorldUnlockTile t)
	{
		if (!WorldUnlockTileType.SKILL.equals(t.getType())) return -1;
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

	/** True if at least one prerequisite of this tile is an area tile id that is unlocked. Used for quest/boss/diary "starting area" eligibility. */
	private boolean hasUnlockedStartingArea(WorldUnlockTile t)
	{
		if (t.getPrerequisites() == null || t.getPrerequisites().isEmpty()) return false;
		for (String prereq : t.getPrerequisites())
		{
			if (!unlockedIds.contains(prereq)) continue;
			WorldUnlockTile areaTile = getTileById(prereq);
			if (areaTile != null && WorldUnlockTileType.AREA.equals(areaTile.getType()))
				return true;
		}
		return false;
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
	 * True if this tile is revealed. Center (0,0) is always revealed; any other tile is revealed
	 * only when at least one cardinal neighbor is claimed (unlocked + action done + claimed).
	 */
	public boolean isRevealed(WorldUnlockTilePlacement placement, Set<String> claimed, List<WorldUnlockTilePlacement> grid)
	{
		int row = placement.getRow(), col = placement.getCol();
		if (row == 0 && col == 0)
			return true; // center (starter) is always revealed

		int[][] deltas = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		java.util.Map<String, String> posToId = new java.util.HashMap<>();
		for (WorldUnlockTilePlacement p : grid)
			posToId.put(p.getRow() + "," + p.getCol(), p.getTile().getId());

		for (int[] d : deltas)
		{
			int nr = row + d[0], nc = col + d[1];
			String neighborId = posToId.get(nr + "," + nc);
			if (neighborId != null && claimed.contains(neighborId))
				return true;
		}
		return false;
	}

	/** Returns the set of claimed tile ids (unlocked and action completed). Only claimed tiles reveal adjacent positions. */
	public Set<String> getClaimedIds()
	{
		if (!loaded) load();
		return Collections.unmodifiableSet(new HashSet<>(claimedIds));
	}

	/**
	 * Marks the tile as claimed (action completed). Call after the player has unlocked and completed the tile's action.
	 * Only unlocked tiles can be claimed. Claiming reveals adjacent tiles. Returns true on success.
	 */
	public boolean claim(String tileId)
	{
		if (!loaded) load();
		if (tileId == null || !unlockedIds.contains(tileId))
			return false;
		if (claimedIds.contains(tileId))
			return true; // already claimed
		claimedIds.add(tileId);
		persistClaimed();
		return true;
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

	/**
	 * Returns tile ids that are either unlocked or revealed on the World Unlock grid.
	 * Used so task requirements like "[skill] [bracket]" (e.g. "Agility 41-50") allow the task
	 * to populate once that skill unlock is visible (revealed), not only when it is unlocked.
	 */
	public Set<String> getUnlockedOrRevealedTileIds()
	{
		Set<String> out = new HashSet<>(getUnlockedIds());
		for (WorldUnlockTilePlacement p : getGrid())
		{
			if (p != null && p.getTile() != null && p.getTile().getId() != null)
				out.add(p.getTile().getId());
		}
		return out;
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

	/** Display name for the configured starter area (from areas.json when available), for use in the World Unlock panel. */
	public String getStarterAreaDisplayName()
	{
		String startId = config.startingArea();
		if (startId == null || startId.isEmpty()) return "";
		Area area = areaGraphService.getArea(startId);
		if (area != null && area.getDisplayName() != null && !area.getDisplayName().isEmpty())
			return area.getDisplayName();
		WorldUnlockTile tile = getTileById(startId);
		if (tile != null && tile.getDisplayName() != null && !tile.getDisplayName().isEmpty())
			return tile.getDisplayName();
		return startId;
	}

	/**
	 * Returns the point cost to unlock this tile when in World Unlock mode.
	 * Cost = tier × tier points (config) × type multiplier (config) for more scaling by difficulty.
	 * Example: tier 2, 25 pts, multiplier 4 → 2 × 25 × 4 = 200. The starter area tile returns 0.
	 */
	public int getTileCost(WorldUnlockTile tile)
	{
		if (tile == null) return 0;
		String startArea = config.startingArea();
		if (startArea != null && startArea.equals(tile.getId()))
			return 0;
		int tier = Math.max(1, tile.getTier());
		int tierPoints = getTierPoints(tile.getTier());
		int multiplier = getMultiplier(tile.getType());
		return tier * tierPoints * multiplier;
	}

	private int getTierPoints(int tier)
	{
		switch (tier)
		{
			case 1: return config.taskTier1Points();
			case 2: return config.taskTier2Points();
			case 3: return config.taskTier3Points();
			case 4: return config.taskTier4Points();
			case 5: return config.taskTier5Points();
			default: return Math.max(1, tier);
		}
	}

	private int getMultiplier(String type)
	{
		if (type == null) return 1;
		switch (type)
		{
			case WorldUnlockTileType.SKILL: return config.worldUnlockSkillMultiplier();
			case WorldUnlockTileType.AREA: return config.worldUnlockAreaMultiplier();
			case WorldUnlockTileType.BOSS: return config.worldUnlockBossMultiplier();
			case WorldUnlockTileType.QUEST: return config.worldUnlockQuestMultiplier();
			case WorldUnlockTileType.ACHIEVEMENT_DIARY: return config.worldUnlockAchievementDiaryMultiplier();
			case WorldUnlockTileType.TASK_FILTER:
			default: return config.worldUnlockSkillMultiplier();
		}
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
		// Area tiles: unlock and claim in one action (no separate "complete action then claim" step)
		if (WorldUnlockTileType.AREA.equals(tile.getType()))
		{
			claimedIds.add(tileId);
			persistClaimed();
		}
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

	/** Clears all unlocked and claimed world-unlock ids and grid state (e.g. on reset). Next getGrid() will re-roll from center. */
	public void clearUnlocked()
	{
		unlockedIds.clear();
		claimedIds.clear();
		persistUnlocked();
		persistClaimed();
		configManager.unsetConfiguration(STATE_GROUP, KEY_WORLD_UNLOCK_GRID_STATE);
	}

	private static final java.util.regex.Pattern DIARY_SUFFIX = java.util.regex.Pattern.compile("_(easy|medium|hard|elite)$");

	/** Diary key from achievement_diary tile id (e.g. varrock_easy -> varrock, lumbridge_draynor_elite -> lumbridge_draynor). */
	public static String getDiaryKeyFromTileId(String tileId)
	{
		if (tileId == null) return null;
		return DIARY_SUFFIX.matcher(tileId).replaceFirst("");
	}

	/** Fallback: area ids that belong to a diary region but are not prerequisites of the diary tile. */
	private static final Map<String, String> AREA_TO_DIARY_FALLBACK;
	static
	{
		Map<String, String> m = new HashMap<>();
		// Kandarin
		m.put("hemenster", "kandarin");
		m.put("catherby", "kandarin");
		m.put("camelot_seers", "kandarin");
		m.put("barbarian_waterfall", "kandarin");
		m.put("piscatoris", "kandarin");
		m.put("yanille", "kandarin");
		m.put("taverley", "kandarin");
		// Karamja (incl. Musa Point, Brimhaven)
		m.put("musa_point", "karamja");
		// Desert
		m.put("nardah_desert", "desert");
		m.put("polnivneach_desert", "desert");
		m.put("sophanem", "desert");
		m.put("uzer_desert", "desert");
		m.put("lassar", "desert");
		m.put("jaldraocht", "desert");
		m.put("necropolis", "desert");
		// Wilderness
		m.put("edgeville", "wilderness");
		m.put("south_central_wilderness", "wilderness");
		m.put("southeast_wilderness", "wilderness");
		m.put("deep_wilderness", "wilderness");
		m.put("northeast_wilderness", "wilderness");
		m.put("north_central_wilderness", "wilderness");
		m.put("northwestern_wilderness", "wilderness");
		m.put("southwestern_wilderness", "wilderness");
		// Morytania
		m.put("port_phasmatys", "morytania");
		m.put("mort_myre_swamp", "morytania");
		m.put("slepe", "morytania");
		m.put("myreditch", "morytania");
		m.put("darkmeyer", "morytania");
		m.put("southern_morytania", "morytania");
		// Western Provinces
		m.put("gnome_stronghold", "western_provinces");
		m.put("ape_atoll", "western_provinces");
		m.put("isafdar", "western_provinces");
		// Fremennik
		m.put("trollheim", "fremennik");
		// Kourend & Kebos
		m.put("hosidius", "kourend_kebos");
		m.put("arceuus", "kourend_kebos");
		m.put("port_piscarilius", "kourend_kebos");
		m.put("lovakengj", "kourend_kebos");
		m.put("shayzien", "kourend_kebos");
		m.put("northern_kourend", "kourend_kebos");
		m.put("kebos_lowlands", "kourend_kebos");
		m.put("kebos_swamp", "kourend_kebos");
		// Falador
		m.put("port_sarim_mudskipper", "falador");
		// Varrock
		m.put("grand_exchange", "varrock");
		AREA_TO_DIARY_FALLBACK = Collections.unmodifiableMap(m);
	}

	/** Builds area id -> diary key from achievement_diary tiles (prerequisites + diary key as area) plus fallback. */
	private Map<String, String> buildAreaIdToDiaryKey()
	{
		Map<String, String> map = new HashMap<>(AREA_TO_DIARY_FALLBACK);
		Set<String> areaTileIds = tiles.stream()
			.filter(t -> WorldUnlockTileType.AREA.equals(t.getType()))
			.map(WorldUnlockTile::getId)
			.collect(Collectors.toSet());
		for (WorldUnlockTile tile : tiles)
		{
			if (!WorldUnlockTileType.ACHIEVEMENT_DIARY.equals(tile.getType())) continue;
			String diaryKey = getDiaryKeyFromTileId(tile.getId());
			if (diaryKey == null) continue;
			if (areaTileIds.contains(diaryKey))
				map.put(diaryKey, diaryKey);
			if (tile.getPrerequisites() != null)
			{
				for (String prereq : tile.getPrerequisites())
					map.put(prereq, diaryKey);
			}
		}
		return map;
	}

	/** Returns the achievement diary key for an area id (e.g. varrock -> varrock, al_kharid -> desert), or null. */
	public String getDiaryKeyForAreaId(String areaId)
	{
		if (!loaded) load();
		if (areaId == null || areaId.isEmpty()) return null;
		if (areaIdToDiaryKey == null)
			areaIdToDiaryKey = buildAreaIdToDiaryKey();
		return areaIdToDiaryKey.get(areaId);
	}

	/**
	 * Returns unlocked achievement diary tier keys for Global task gating.
	 * Format: "diaryKey_difficulty" (e.g. varrock_1, desert_2). easy=1, medium=2, hard=3, elite=4.
	 */
	public Set<String> getUnlockedDiaryTierKeys()
	{
		if (!loaded) load();
		Set<String> out = new HashSet<>();
		for (WorldUnlockTile tile : tiles)
		{
			if (!WorldUnlockTileType.ACHIEVEMENT_DIARY.equals(tile.getType()) || !unlockedIds.contains(tile.getId()))
				continue;
			TaskLink link = tile.getTaskLink();
			if (link == null) continue;
			String diaryKey = getDiaryKeyFromTileId(tile.getId());
			if (diaryKey == null) continue;
			int difficulty = link.getDifficulty() != null ? link.getDifficulty() : 1;
			out.add(diaryKey + "_" + difficulty);
		}
		return out;
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
			case WorldUnlockTileType.AREA:
				// Tasks where task.area/areas contains this tile's id (area tile id = area id)
				String areaId = tile.getId();
				return all.stream()
					.filter(t -> t.getRequiredAreaIds().contains(areaId))
					.collect(Collectors.toList());
			case WorldUnlockTileType.SKILL:
				// taskType matches skillName, difficulty matches tier from level band (1-39->1, 40-59->2, 60-79->3, 80-89->4, 90-99->5)
				String skillName = link.getSkillName();
				int tier = levelBandToTier(link.getLevelMin() != null ? link.getLevelMin() : 1);
				return all.stream()
					.filter(t -> (skillName == null || skillName.equalsIgnoreCase(t.getTaskType())) && t.getDifficulty() == tier)
					.collect(Collectors.toList());
			case WorldUnlockTileType.TASK_FILTER:
				return all.stream()
					.filter(t -> matchTaskFilter(tile, t, link))
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

	private boolean matchTaskFilter(WorldUnlockTile tile, TaskDefinition t, TaskLink link)
	{
		if (link.getTaskType() != null && !link.getTaskType().equalsIgnoreCase(t.getTaskType()))
			return false;
		if (link.getDifficulty() != null && link.getDifficulty() != t.getDifficulty())
			return false;
		// Achievement diary: match by task area -> diary key (task's required area must belong to this diary)
		if (WorldUnlockTileType.ACHIEVEMENT_DIARY.equals(tile.getType()) && com.leaguescape.constants.TaskTypes.ACHIEVEMENT_DIARY.equalsIgnoreCase(link.getTaskType()))
		{
			List<String> areaIds = t.getRequiredAreaIds();
			if (areaIds == null || areaIds.isEmpty()) return false;
			String diaryKey = getDiaryKeyFromTileId(tile.getId());
			if (diaryKey == null) return false;
			boolean anyAreaMatches = areaIds.stream()
				.anyMatch(areaId -> diaryKey.equals(getDiaryKeyForAreaId(areaId)));
			if (!anyAreaMatches) return false;
		}
		else if (link.getRequirementsContains() != null && !link.getRequirementsContains().isEmpty())
		{
			String req = t.getRequirements();
			if (req == null || !req.toLowerCase().contains(link.getRequirementsContains().toLowerCase()))
				return false;
		}
		return true;
	}
}
